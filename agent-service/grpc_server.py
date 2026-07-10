"""
gRPC Agent 服务端
提供双向流 Chat RPC，集成 LLM Function Calling + RAG 检索，
Agent 通过 gRPC 调 Java 侧执行业务操作。

共享核心位于 agent_core.py（gRPC 和 FastAPI 共用），本文件只保留 gRPC 特有逻辑。

启动方式：
    python grpc_server.py                     # 独立运行（生产模式，不依赖 FastAPI）
    # 或与 FastAPI 同进程：在 main.py 中 import 本模块并调用 start_grpc_server()
"""
import json
import logging
import threading
import os
from concurrent import futures
from typing import Generator, Optional

import grpc

# 项目内模块
from config import settings
from agent_core import AgentSession, LOCAL_TOOL_NAMES
from rag_engine import RAGEngine
from session_store import SessionStore
from agent_pb2 import (
    ClientMessage, ServerMessage, ToolCall, ToolResult, Error,
)
import agent_pb2_grpc

logger = logging.getLogger(__name__)


# ============================================================
# gRPC 工具函数
# ============================================================

def to_grpc_tool_call(tc: dict) -> ToolCall:
    """将 agent_core 的 tool_call dict 转换为 protobuf ToolCall"""
    return ToolCall(
        call_id=tc["call_id"],
        tool_name=tc["tool_name"],
        arguments=tc["arguments"],
    )


# ============================================================
# gRPC Service 实现
# ============================================================

class AgentGrpcServicer(agent_pb2_grpc.AgentServiceServicer):
    """Agent gRPC 服务：双向流 Chat"""

    def __init__(self, rag_engine: Optional[RAGEngine] = None,
                 session_store: Optional[SessionStore] = None):
        self.sessions: dict[str, AgentSession] = {}
        self._rag_engine = rag_engine
        self._session_store = session_store

    def _get_rag_engine(self) -> Optional[RAGEngine]:
        """懒加载 RAGEngine（支持从 main.py 传入或自初始化）"""
        if self._rag_engine is None:
            try:
                logger.info("RAGEngine 懒加载...")
                engine = RAGEngine()
                engine.initialize()
                self._rag_engine = engine
                logger.info("RAGEngine 懒加载成功")
            except Exception as e:
                logger.error("RAGEngine 懒加载失败: %s", e)
                self._rag_engine = None
        return self._rag_engine

    def _get_or_create_session(self, session_id: str) -> AgentSession:
        if session_id not in self.sessions:
            session = AgentSession(session_id)
            # 尝试从 Redis 恢复历史消息
            if self._session_store:
                messages = self._session_store.load_session(session_id)
                if messages is not None:
                    session.messages = messages
                    logger.info("已从 Redis 恢复会话 [%s]: %d 条消息",
                                session_id, len(messages))
            self.sessions[session_id] = session
            logger.info("创建新会话: %s", session_id)
        return self.sessions[session_id]

    def _persist_session(self, session: AgentSession):
        """将当前会话持久化到 Redis"""
        if self._session_store and session:
            self._session_store.save_session(session.session_id, session.messages)

    def Chat(
        self, request_iterator: Generator[ClientMessage, None, None],
        context: grpc.ServicerContext,
    ) -> Generator[ServerMessage, None, None]:
        """
        双向流 Chat 处理核心逻辑：

        1. 接收 ClientMessage（user_message 或 tool_result）
        2. 调用 LLM（带 Function Calling）
        3. 如果 LLM 返回 tool_call → 发送 ToolCall 给 Java → 等待 ToolResult
        4. 如果 LLM 返回文本 → 发送 text 给 Java → 等待下一条消息
        """
        session: Optional[AgentSession] = None

        try:
            for request in request_iterator:
                # --- 初始化 / 获取会话 ---
                if session is None:
                    session = self._get_or_create_session(request.session_id or "default")
                    logger.info("会话 %s 开始处理请求", session.session_id)

                # --- Step 1: 处理收到的消息 ---
                if request.HasField("user_message"):
                    question = request.user_message.strip()
                    if not question:
                        continue
                    logger.info("[%s] 用户: %s", session.session_id, question)
                    session.add_user_message(question)

                elif request.HasField("tool_result"):
                    tr = request.tool_result
                    logger.info("[%s] Tool结果: %s.%s",
                                session.session_id, tr.call_id, tr.result[:80])
                    session.add_tool_result(tr.call_id, tr.result)

                else:
                    logger.warning("[%s] 未知消息类型", session.session_id if session else "?")
                    continue

                # --- Step 2: 循环调用 LLM 直到产生文本或等待工具结果 ---
                def _local_tool_handler(tool_name,args_dict,call_id):
                    if tool_name=="search_knowledge":
                        rag_engine =  self._get_rag_engine()
                        if  rag_engine:
                            answer,_=rag_engine.ask(args_dict.get("question",""))
                            return answer
                        return json.dumps({"error":"RAGEngine 未初始化"})
                    return json.dumps({"error":"工具 %s 未实现" % tool_name})

                result = session.run_turn(local_tool_handler= _local_tool_handler)

                if result["type"] == "remote_tool":
                    for tc in result["tools"]:
                        logger.info("发送工具调用: %s", tc)
                        yield ServerMessage(tool_call=to_grpc_tool_call(tc))
                    self._persist_session(session)

                elif result["type"] == "text":
                    logger.info("发送文本: %s", result["text"])
                    yield ServerMessage(text = result["context"])
                    self._persist_session(session)


        except grpc.RpcError as e:
            logger.error("gRPC 错误: %s", e)
            yield ServerMessage(
                error=Error(code="RPC_ERROR", message=str(e)),
            )
        except Exception as e:
            logger.error("Chat 处理异常: %s", e, exc_info=True)
            yield ServerMessage(
                error=Error(code="INTERNAL_ERROR", message=str(e)),
            )
        finally:
            if session:
                self._persist_session(session)
                logger.info("会话 %s 处理完毕, 已持久化", session.session_id)
                # 会话保留在内存中以便后续对话
                # 如果不需要保留，可以从 self.sessions 中移除


# ============================================================
# 服务器启动
# ============================================================

_grpc_server: Optional[grpc.Server] = None


def serve(port: int = 50051,
          max_workers: int = 10,
          rag_engine: Optional[RAGEngine] = None,
          session_store: Optional[SessionStore] = None) -> grpc.Server:
    """启动 gRPC 服务器（阻塞）"""
    global _grpc_server

    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=max_workers),
        maximum_concurrent_rpcs=50,
    )
    agent_pb2_grpc.add_AgentServiceServicer_to_server(
        AgentGrpcServicer(rag_engine=rag_engine, session_store=session_store), server
    )
    server.add_insecure_port(f"0.0.0.0:{port}")

    server.start()
    _grpc_server = server
    logger.info("gRPC Agent 服务已启动，监听端口 %d", port)
    server.wait_for_termination()
    return server


def start_grpc_server_in_thread(port: int = 50051,
                                max_workers: int = 10,
                                rag_engine: Optional[RAGEngine] = None,
                                session_store: Optional[SessionStore] = None) -> threading.Thread:
    """在后台线程启动 gRPC 服务器"""
    thread = threading.Thread(
        target=serve,
        args=(port, max_workers, rag_engine, session_store),
        daemon=True,
        name="grpc-server",
    )
    thread.start()
    logger.info("gRPC Agent 后台线程已启动")
    return thread


def stop_grpc_server():
    """停止 gRPC 服务器"""
    global _grpc_server
    if _grpc_server:
        _grpc_server.stop(grace=5)
        logger.info("gRPC 服务器已停止")


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    logger.info("启动 gRPC Agent 服务...")
    serve()
