"""
Agent 核心模块
共享给 FastAPI（HTTP 调试）和 gRPC（生产）使用的 Agent 逻辑：
- TOOL_DEFINITIONS / LOCAL_TOOL_NAMES
- AgentSession（LLM 调用、Function Calling、对话管理）

使用方式：
    from agent_core import AgentSession, LOCAL_TOOL_NAMES
"""
import json
import logging

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage, ToolMessage

from config import settings

logger = logging.getLogger(__name__)

# ============================================================
# Function Calling 工具定义
# ============================================================

TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "search_shop",
            "description": "搜索/推荐店铺，支持按名称、区域、店铺分类查询。用户说'推荐奶茶店'时用type='奶茶'，'朝阳区的店'时用area='朝阳区'",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "店铺名称关键词，用户指定了具体店名时使用，如'喜茶'、'海底捞'"},
                    "area": {"type": "string", "description": "区域/商圈，用户按位置找店时使用，如'朝阳区'、'陆家嘴'"},
                    "type": {"type": "string", "description": "店铺分类，用户按品类找店时使用，如'奶茶'、'火锅'、'川菜'"}
                },
                "additionalProperties": False
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "query_voucher_of_shop",
            "description": "查询店铺的优惠券/代金券信息，包括普通券和秒杀券",
            "parameters": {
                "type": "object",
                "properties": {
                    "shop_name": {"type": "string", "description": "店铺名称，用于查找该店铺的优惠券"}
                },
                "required": ["shop_name"],
                "additionalProperties": False
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "create_blog",
            "description": "为用户创建探店笔记/博客，需要店铺ID、标题和内容。创建前先询问用户想写的标题和内容",
            "parameters": {
                "type": "object",
                "properties": {
                    "shop_id": {"type": "integer", "description": "目标店铺ID"},
                    "title": {"type": "string", "description": "笔记标题"},
                    "content": {"type": "string", "description": "笔记正文内容，用户对店铺的评价和描述"}
                },
                "required": ["shop_id", "title", "content"],
                "additionalProperties": False
            }
        }
    },
]

# 在 Python 侧执行、不需要发往 Java 的工具名集合
LOCAL_TOOL_NAMES = {"search_knowledge"}

# search_knowledge 也加进 TOOL_DEFINITIONS，让 LLM 知道可以调用
TOOL_DEFINITIONS.append({
    "type": "function",
    "function": {
        "name": "search_knowledge",
        "description": "搜索店铺知识库，查询店铺的营业时间、位置、推荐菜品、评分介绍等店铺信息。用户问'这家店怎么样''有什么推荐菜'时使用",
        "parameters": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "用户关于店铺的知识性问题"}
            },
            "required": ["question"],
            "additionalProperties": False
        }
    }
})


# ============================================================
# Agent 会话管理
# ============================================================

class AgentSession:
    """管理单个会话的对话历史和 LLM 实例

    支持消息窗口管理：当消息数超过 max_messages 时自动截断旧消息。
    如需持久化，配合 session_store.SessionStore 使用。
    """

    SYSTEM_PROMPT = """你是校园AI服务平台的智能助手,你可以通过 Tool 执行以下操作：search_shop、query_voucher_of_shop、create_blog。
注意：当你需要回答关于店铺信息、营业时间、位置等知识性问题时，
请先调用 search_knowledge(question) 获取相关知识，再基于这些信息回答.

## 可用工具
1. **search_shop** — 搜索店铺。当用户想找店、推荐店时使用
2. **query_voucher_of_shop** — 查店铺优惠券。用户问"优惠券""代金券""活动"时使用
3. **create_blog** — 创建探店笔记。用户说"发笔记""写评价""推荐"时使用

## 行为准则
- 如果用户问的是关于店铺信息、优惠信息、推荐等，先判断能否用工具获取实时数据
- 使用工具前，先友好地向用户确认需求
- 工具执行结果返回后，用自然语言整理给用户
- 回答简洁清晰，使用中文
- 如果用户意图不明确，主动询问澄清
"""

    def __init__(self, session_id: str, max_messages: int = 12):
        self.session_id = session_id
        self.messages = []
        self.max_messages = max_messages
        self.llm = ChatOpenAI(
            model=settings.llm_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_api_base_url,
            temperature=0.3,
            max_tokens=1024,
        )
        # 绑定工具
        self.llm_with_tools = self.llm.bind_tools(TOOL_DEFINITIONS)

    def _apply_window(self):
        """消息窗口管理：超过 max_messages 时从头部截断"""
        if len(self.messages) > self.max_messages:
            dropped = len(self.messages) - self.max_messages
            logger.debug("窗口截断 [%s]: 丢弃 %d 条", self.session_id, dropped)
            self.messages = self.messages[-self.max_messages:]

    def add_user_message(self, content: str):
        self.messages.append(HumanMessage(content=content))
        self._apply_window()

    def add_tool_result(self, call_id: str, result: str):
        self.messages.append(ToolMessage(content=result, tool_call_id=call_id))
        self._apply_window()

    def invoke_llm(self) -> AIMessage:
        """调用 LLM，返回响应"""
        response = self.llm_with_tools.invoke(self.messages)
        self.messages.append(response)
        self._apply_window()
        return response

    def has_tool_calls(self, response: AIMessage) -> bool:
        return bool(response.tool_calls)

    def get_tool_calls(self, response: AIMessage) -> list:
        """获取工具调用列表，格式化为 dict 列表（跨 gRPC / HTTP 通用）"""
        result = []
        for tc in response.tool_calls:
            result.append({
                "call_id": tc["id"],
                "tool_name": tc["name"],
                "arguments": json.dumps(tc["args"], ensure_ascii=False),
            })
        return result

    def run_turn(self, local_tool_handler=None) -> dict:
        """
        执行完整 Agent 循环，直到产生文本或需要远程工具。

        统一了 gRPC 和 HTTP 两侧的 while 循环逻辑。
        调用方只需判断返回值的 type 字段。

        Args:
            local_tool_handler: 可选的回调，执行本地工具
                callable(tool_name, args_dict, call_id) -> str
                返回的字符串会通过 add_tool_result 注入给 LLM

        Returns:
            {"type": "text", "content": str}                — 最终文本回复
            {"type": "remote_tool", "tools": [dict, ...]}   — 需要调用方处理远程工具
        """
        for _ in range(10):  # 防止死循环
            response = self.invoke_llm()

            if self.has_tool_calls(response):
                tool_calls = self.get_tool_calls(response)
                local_tools = [tc for tc in tool_calls if tc["tool_name"] in LOCAL_TOOL_NAMES]
                remote_tools = [tc for tc in tool_calls if tc["tool_name"] not in LOCAL_TOOL_NAMES]

                # 执行本地工具
                for tc in local_tools:
                    logger.info("执行本地工具 %s", tc["tool_name"])
                    if local_tool_handler:
                        args = json.loads(tc["arguments"])
                        try:
                            result = local_tool_handler(tc["tool_name"], args, tc["call_id"])
                            self.add_tool_result(tc["call_id"], result)
                        except Exception as e:
                            self.add_tool_result(
                                tc["call_id"],
                                json.dumps({"error": str(e)})
                            )
                    else:
                        self.add_tool_result(
                            tc["call_id"],
                            json.dumps({"error": f"本地工具 {tc['tool_name']} 未注册处理器"})
                        )

                # 有远程工具 → 返回给调用方处理（Java 执行 / 调试提示）
                if remote_tools:
                    return {"type": "remote_tool", "tools": remote_tools}

                # 只有本地工具 → 继续下一轮 LLM 调用

            else:
                return {"type": "text", "content": response.content}

        return {"type": "text", "content": "处理超时，请重试"}
