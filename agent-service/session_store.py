"""
会话持久化与窗口管理
基于 Redis 的 AgentSession 消息序列化/反序列化，支持窗口截断。

使用方式：
    store = SessionStore(redis_url="redis://localhost:6379/0")
    store.save_session("session_1", messages)
    messages = store.load_session("session_1")
"""
import json
import logging
from typing import Optional

from langchain_core.messages import HumanMessage, AIMessage, ToolMessage

logger = logging.getLogger(__name__)


class SessionStore:
    """Redis 会话存储器，带消息窗口管理"""

    DEFAULT_MAX_MESSAGES = 12  # 约 30 轮对话

    def __init__(self, redis_url: str, max_messages: int = DEFAULT_MAX_MESSAGES):
        self.max_messages = max_messages
        self._redis = None
        self.available = False

        try:
            import redis as redis_mod
            self._redis = redis_mod.from_url(redis_url, decode_responses=True)
            self._redis.ping()
            self.available = True
            logger.info("SessionStore Redis 连接成功: %s", redis_url)
        except Exception as e:
            logger.warning("SessionStore Redis 不可用，会话持久化已禁用: %s", e)

    # ============================================================
    # 消息序列化 / 反序列化
    # ============================================================

    @staticmethod
    def serialize_messages(messages: list) -> list[dict]:
        """将 LangChain Message 列表序列化为 JSON 安全 dict 列表"""
        result = []
        for msg in messages:
            if isinstance(msg, HumanMessage):
                result.append({"role": "human", "content": msg.content})
            elif isinstance(msg, AIMessage):
                entry = {"role": "ai", "content": msg.content or ""}
                if msg.tool_calls:
                    entry["tool_calls"] = [
                        {"id": tc["id"], "name": tc["name"], "args": tc["args"]}
                        for tc in msg.tool_calls
                    ]
                result.append(entry)
            elif isinstance(msg, ToolMessage):
                result.append({
                    "role": "tool",
                    "content": msg.content,
                    "tool_call_id": msg.tool_call_id,
                })
            else:
                logger.warning("跳过未知消息类型: %s", type(msg).__name__)
        return result

    @staticmethod
    def deserialize_messages(data: list[dict]) -> list:
        """将 JSON dict 列表还原为 LangChain Message 列表"""
        messages = []
        for d in data:
            role = d.get("role")
            if role == "human":
                messages.append(HumanMessage(content=d.get("content", "")))
            elif role == "ai":
                content = d.get("content", "")
                tool_calls = d.get("tool_calls")
                if tool_calls:
                    messages.append(AIMessage(content=content, tool_calls=tool_calls))
                else:
                    messages.append(AIMessage(content=content))
            elif role == "tool":
                messages.append(ToolMessage(
                    content=d.get("content", ""),
                    tool_call_id=d.get("tool_call_id", ""),
                ))
            else:
                logger.warning("跳过未知 role: %s", role)
        return messages

    # ============================================================
    # 窗口管理
    # ============================================================

    def apply_window(self, messages: list) -> list:
        """保留最后 N 条消息，超出时从头部截断"""
        if len(messages) > self.max_messages:
            dropped = len(messages) - self.max_messages
            logger.info("窗口截断: 丢弃 %d 条旧消息，保留最后 %d 条",
                        dropped, self.max_messages)
            return messages[-self.max_messages:]
        return messages

    # ============================================================
    # Redis 读写
    # ============================================================

    def _key(self, session_id: str) -> str:
        return f"agent:session:{session_id}"

    def save_session(self, session_id: str, messages: list) -> None:
        """将会话消息持久化到 Redis，TTL 24 小时"""
        if not self.available:
            return
        try:
            data = self.serialize_messages(messages)
            raw = json.dumps(data, ensure_ascii=False)
            #设置过期时间，24小时。 可以用set key
            self._redis.setex(self._key(session_id), 86400, raw)
        except Exception as e:
            logger.error("保存会话失败 [%s]: %s", session_id, e)

    def load_session(self, session_id: str) -> Optional[list]:
        """从 Redis 加载会话消息，返回时已应用窗口截断"""
        if not self.available:
            return None
        try:
            raw = self._redis.get(self._key(session_id))
            if raw is None:
                return None
            data = json.loads(raw)
            messages = self.deserialize_messages(data)
            logger.info("恢复会话 [%s]: %d 条消息", session_id, len(messages))
            return self.apply_window(messages)
        except Exception as e:
            logger.error("加载会话失败 [%s]: %s", session_id, e)
            return None

    def delete_session(self, session_id: str) -> None:
        """从 Redis 删除会话"""
        if not self.available:
            return
        try:
            self._redis.delete(self._key(session_id))
        except Exception as e:
            logger.error("删除会话失败 [%s]: %s", session_id, e)
