"""
FastAPI 应用入口
RAG Agent 微服务，提供智能问答、数据加载、健康检查接口
同时后台启动 gRPC Agent 服务（与 Java 双向流通信）
"""

# cd agent-service
#  uvicorn main:app --host 0.0.0.0 --port 8000

import logging
import os
from contextlib import asynccontextmanager
from typing import Optional


from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import Json

from config import settings
from models import AskRequest, AskResponse, HealthResponse, IngestResponse
from rag_engine import RAGEngine
from agent_core import AgentSession, LOCAL_TOOL_NAMES
from session_store import SessionStore

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# 全局 RAG 引擎实例
rag_engine: RAGEngine = None  # type: ignore
# 全局 SessionStore（Redis 会话持久化）
session_store: SessionStore = None  # type: ignore
# HTTP Chat 会话存储（调试用，进程内）
_chat_sessions: dict[str, AgentSession] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    global rag_engine, session_store

    # 初始化 SessionStore（Redis 会话持久化，不可用时静默降级）
    session_store = SessionStore(settings.redis_url)

    logger.info("正在初始化 RAG 引擎...")
    try:
        rag_engine = RAGEngine()
        rag_engine.initialize()
        logger.info("RAG 引擎初始化完成")
    except Exception as e:
        logger.error("RAG 引擎初始化失败: %s", e)
        logger.warning("RAG 引擎将无法使用，请检查配置后调用 /ingest 重新加载")
        rag_engine = None  # type: ignore

    # 启动 gRPC Agent 服务（后台线程）
    try:
        from grpc_server import start_grpc_server_in_thread
        grpc_port = int(os.getenv("AGENT_GRPC_PORT", "50051"))
        start_grpc_server_in_thread(port=grpc_port, rag_engine=rag_engine,
                                     session_store=session_store)
        logger.info("gRPC Agent 服务已在端口 %d 后台启动", grpc_port)
    except Exception as e:
        logger.warning("gRPC Agent 服务启动失败: %s", e)

    yield

    # 关闭时清理资源
    if rag_engine:
        rag_engine.close()
        logger.info("RAG 引擎已关闭")
    try:
        from grpc_server import stop_grpc_server
        stop_grpc_server()
    except Exception:
        pass
    logger.info("gRPC 服务已关闭")


app = FastAPI(
    title="校园AI服务平台 Agent Service",
    description="基于 FastAPI + LangChain + gRPC 的校园AI Agent 服务，通过 gRPC 双向流与 Spring Boot 通信",
    version="2.0.0",
    lifespan=lifespan,
)

# CORS 配置 - 允许来自 Spring Boot 前端的请求
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def root():
    """服务根路径，返回框架信息"""
    return {
        "service": "校园AI服务平台 Agent Service",
        "framework": {
            "http": "FastAPI",
            "ai": "LangChain",
            "transport": "gRPC bidirectional streaming",
        },
        "endpoints": {
            "grpc": "localhost:50051",
            "http_health": "/health",
            "http_ask": "/ask",
            "http_ingest": "/ingest",
        },
    }


@app.get("/health", response_model=HealthResponse)
async def health():
    """健康检查接口"""
    if rag_engine is None:
        return HealthResponse(
            status="unavailable",
            vector_count=0,
            collections=[],
        )
    try:
        count = rag_engine.get_vector_count()
        collections = rag_engine.get_collections()
        return HealthResponse(
            status="ok",
            vector_count=count,
            collections=collections,
        )
    except Exception as e:
        logger.error("健康检查异常: %s", e)
        return HealthResponse(
            status="error",
            vector_count=0,
            collections=[],
        )


@app.post("/ask", response_model=AskResponse)
async def ask(request: AskRequest):
    """问答接口"""
    if rag_engine is None:
        raise HTTPException(
            status_code=503,
            detail="RAG 引擎未初始化，请先调用 /ingest 加载数据",
        )
    if not request.question or not request.question.strip():
        raise HTTPException(
            status_code=400,
            detail="问题不能为空",
        )
    try:
        response,score = rag_engine.ask(request.question.strip())
        return AskResponse(answer=response, score=score)
    except Exception as e:
        logger.error("问答处理失败: %s", e, exc_info=True)
        raise HTTPException(
            status_code = 500,
            detail = f"问答处理失败:{str(e)}"
        )


@app.post("/ingest", response_model=IngestResponse)
async def ingest():
    """重新加载数据到向量库"""
    global rag_engine
    try:
        logger.info("重新加载数据...")
        if rag_engine:
            rag_engine.close()
        rag_engine = RAGEngine()
        total = rag_engine.rebuild_vector_store()
        logger.info(f"数据加载完成,共{total}个文件")
        return IngestResponse(
            success=True,
            total_documents=total,
            collections={
                "hmdp": total,
            },
        )
    except Exception as e:
        logger.error("数据加载失败: %s", e, exc_info=True)
        raise HTTPException(
            status_code=500,
            detail = f"数据加载失败:{str(e)}"
        )


@app.post("/chat")
async def chat(data: dict):
    """HTTP Chat 调试接口
    与 gRPC Agent 共用同一套 AgentSession 逻辑。
    - 本地工具（search_knowledge）直接执行
    - 远程工具（search_shop 等）返回提示，告知需通过 Spring Boot 调用
    """
    global _chat_sessions, session_store

    question = data.get("question", "").strip()
    session_id = data.get("session_id", "http_default")

    if not question:
        raise HTTPException(status_code=400, detail="问题不能为空")

    # 从内存缓存获取会话，或从 Redis 恢复，或新建
    if session_id not in _chat_sessions:
        session = AgentSession(session_id)
        if session_store:
            messages = session_store.load_session(session_id)
            if messages is not None:
                session.messages = messages
                logger.info("已从 Redis 恢复 HTTP 会话 [%s]: %d 条消息",
                            session_id, len(messages))
        _chat_sessions[session_id] = session
        logger.info("创建 HTTP Chat 会话: %s", session_id)
    session = _chat_sessions[session_id]
    session.add_user_message(question)

    logger.info("[HTTP Chat][%s] 用户: %s", session_id, question)

    # Agent 循环（最多 10 轮，防止死循环）
    # 改造后
    def _local_tool_handler(tool_name, args_dict, call_id):
        if tool_name == "search_knowledge":
            global rag_engine
            if rag_engine:
                answer, _ = rag_engine.ask(args_dict.get("question", ""))
                return answer
            return Json.dumps({"error": "知识库未初始化"})
        return Json.dumps({"error": f"未知本地工具: {tool_name}"})

    result = session.run_turn(local_tool_handler=_local_tool_handler)

    if result["type"] == "remote_tool":
        return {"answer": None, "mode_hint": "...", "pending_java_tools": [...]}
    elif result["type"] == "text":
        return {"answer": result["content"], "session_id": session_id}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=True,
    )
