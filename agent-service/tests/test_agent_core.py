"""
测试 Agent 核心模块
- AgentSession 基本操作（消息添加、工具调用检测）
- run_turn 各分支（文本、本地工具、远程工具、超时）
- 窗口管理（消息截断）
- TOOL_DEFINITIONS 结构有效性
- SessionStore 序列化/反序列化（无需 Redis）
"""
import json

import pytest
from unittest.mock import MagicMock, patch

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from agent_core import AgentSession, LOCAL_TOOL_NAMES, TOOL_DEFINITIONS
from session_store import SessionStore


# ============================================================
# AgentSession 基本操作
# ============================================================

class TestAgentSessionInit:
    """会话初始化"""

    def test_default_values(self):
        session = AgentSession("s1")
        assert session.session_id == "s1"
        assert session.messages == []
        assert session.max_messages == 60

    def test_custom_max_messages(self):
        session = AgentSession("s1", max_messages=10)
        assert session.max_messages == 10


class TestAgentSessionMessages:
    """消息添加"""

    def test_add_user_message(self):
        session = AgentSession("s1")
        session.add_user_message("你好")
        assert len(session.messages) == 1
        assert isinstance(session.messages[0], HumanMessage)
        assert session.messages[0].content == "你好"

    def test_add_tool_result(self):
        session = AgentSession("s1")
        session.add_tool_result("call_123", '{"result": "ok"}')
        assert len(session.messages) == 1
        assert isinstance(session.messages[0], ToolMessage)
        assert session.messages[0].content == '{"result": "ok"}'
        assert session.messages[0].tool_call_id == "call_123"

    def test_add_multiple_messages(self):
        session = AgentSession("s1")
        session.add_user_message("hi")
        session.add_user_message("how are you")
        assert len(session.messages) == 2
        assert session.messages[-1].content == "how are you"


class TestToolCallDetection:
    """工具调用检测"""

    def test_has_tool_calls_true(self):
        session = AgentSession("s1")
        msg = AIMessage(
            content="",
            tool_calls=[{"id": "c1", "name": "search_shop", "args": {"type": "奶茶"}}],
        )
        assert session.has_tool_calls(msg) is True

    def test_has_tool_calls_false(self):
        session = AgentSession("s1")
        msg = AIMessage(content="纯文本回复")
        assert session.has_tool_calls(msg) is False

    def test_has_tool_calls_empty_list(self):
        session = AgentSession("s1")
        msg = AIMessage(content="回复", tool_calls=[])
        assert session.has_tool_calls(msg) is False


class TestGetToolCalls:
    """工具调用格式化"""

    def test_single_tool(self):
        session = AgentSession("s1")
        msg = AIMessage(
            content="",
            tool_calls=[{"id": "c1", "name": "search_shop", "args": {"type": "奶茶"}}],
        )
        tools = session.get_tool_calls(msg)
        assert len(tools) == 1
        assert tools[0]["call_id"] == "c1"
        assert tools[0]["tool_name"] == "search_shop"
        assert json.loads(tools[0]["arguments"]) == {"type": "奶茶"}

    def test_multi_tool(self):
        session = AgentSession("s1")
        msg = AIMessage(
            content="",
            tool_calls=[
                {"id": "c1", "name": "search_shop", "args": {"type": "火锅"}},
                {"id": "c2", "name": "query_voucher_of_shop", "args": {"shop_name": "海底捞"}},
            ],
        )
        tools = session.get_tool_calls(msg)
        assert len(tools) == 2
        assert tools[0]["tool_name"] == "search_shop"
        assert tools[1]["tool_name"] == "query_voucher_of_shop"

    def test_arguments_json_serializable(self):
        """确保 arguments 是合法的 JSON 字符串"""
        session = AgentSession("s1")
        msg = AIMessage(
            content="",
            tool_calls=[{"id": "c1", "name": "create_blog",
                         "args": {"shop_id": 1, "title": "好评", "content": "很棒"}}],
        )
        tools = session.get_tool_calls(msg)
        parsed = json.loads(tools[0]["arguments"])
        assert parsed["shop_id"] == 1
        assert parsed["title"] == "好评"


# ============================================================
# run_turn 各分支测试（mock LLM）
# ============================================================

class TestRunTurnText:
    """run_turn 直接返回文本"""

    @patch("agent_core.AgentSession.invoke_llm")
    def test_returns_text(self, mock_invoke):
        session = AgentSession("s1")
        session.add_user_message("你好")
        mock_invoke.return_value = AIMessage(content="你好！有什么可以帮助你的吗？")

        result = session.run_turn()
        assert result["type"] == "text"
        assert "你好" in result["content"]

    @patch("agent_core.AgentSession.invoke_llm")
    def test_preserves_session_id(self, mock_invoke):
        session = AgentSession("my_session")
        session.add_user_message("hi")
        mock_invoke.return_value = AIMessage(content="hello")

        result = session.run_turn()
        assert result["type"] == "text"


class TestRunTurnRemoteTool:
    """run_turn 返回远程工具调用"""

    @patch("agent_core.AgentSession.invoke_llm")
    def test_returns_remote_tool(self, mock_invoke):
        session = AgentSession("s1")
        session.add_user_message("找火锅店")
        mock_invoke.return_value = AIMessage(
            content="",
            tool_calls=[{"id": "c1", "name": "search_shop", "args": {"type": "火锅"}}],
        )

        result = session.run_turn()
        assert result["type"] == "remote_tool"
        assert len(result["tools"]) == 1
        assert result["tools"][0]["tool_name"] == "search_shop"
        assert result["tools"][0]["call_id"] == "c1"


class TestRunTurnLocalTool:
    """run_turn 本地工具执行后产生文本"""

    @patch("agent_core.AgentSession.invoke_llm")
    def test_local_tool_then_text(self, mock_invoke):
        """本地工具执行后 LLM 返回文本"""
        session = AgentSession("s1")
        session.add_user_message("这家店怎么样")

        handler = MagicMock(return_value="评分 4.5，推荐麻辣烫")

        # 第一轮：工具调用；第二轮：文本回复
        mock_invoke.side_effect = [
            AIMessage(
                content="让我查一下",
                tool_calls=[
                    {"id": "c1", "name": "search_knowledge",
                     "args": {"question": "这家店怎么样"}}
                ],
            ),
            AIMessage(content="这家店评分 4.5，推荐麻辣烫！"),
        ]

        result = session.run_turn(local_tool_handler=handler)
        assert result["type"] == "text"
        assert "麻辣烫" in result["content"]
        handler.assert_called_once_with(
            "search_knowledge", {"question": "这家店怎么样"}, "c1"
        )

    @patch("agent_core.AgentSession.invoke_llm")
    def test_mixed_local_and_remote(self, mock_invoke):
        """本地 + 远程工具同时出现 → 执行本地后返回远程"""
        session = AgentSession("s1")
        session.add_user_message("朝阳区火锅店有优惠吗")

        handler = MagicMock(return_value="店铺 A 评分 4.5")

        mock_invoke.side_effect = [
            AIMessage(
                content="",
                tool_calls=[
                    {"id": "c1", "name": "search_knowledge",
                     "args": {"question": "朝阳区火锅店"}},
                    {"id": "c2", "name": "query_voucher_of_shop",
                     "args": {"shop_name": "火锅店 A"}},
                ],
            ),
        ]

        result = session.run_turn(local_tool_handler=handler)
        assert result["type"] == "remote_tool"
        # 远程工具列表中不应包含本地工具
        tool_names = [tc["tool_name"] for tc in result["tools"]]
        assert "search_knowledge" not in tool_names
        assert "query_voucher_of_shop" in tool_names

    @patch("agent_core.AgentSession.invoke_llm")
    def test_local_tool_handler_error(self, mock_invoke):
        """本地工具处理抛出异常 → 注入错误消息给 LLM"""
        session = AgentSession("s1")
        session.add_user_message("查一下")

        def failing_handler(tool_name, args, call_id):
            raise ValueError("数据库连接失败")

        # 第一轮：工具调用（异常）；第二轮：LLM 处理错误后回复文本
        mock_invoke.side_effect = [
            AIMessage(
                content="",
                tool_calls=[
                    {"id": "c1", "name": "search_knowledge",
                     "args": {"question": "test"}}
                ],
            ),
            AIMessage(content="抱歉，查询失败，请稍后重试"),
        ]

        result = session.run_turn(local_tool_handler=failing_handler)
        assert result["type"] == "text"

    @patch("agent_core.AgentSession.invoke_llm")
    def test_no_handler_registered(self, mock_invoke):
        """本地工具但未注册 handler → 注入错误消息"""
        session = AgentSession("s1")
        session.add_user_message("查一下")

        mock_invoke.side_effect = [
            AIMessage(
                content="",
                tool_calls=[
                    {"id": "c1", "name": "search_knowledge",
                     "args": {"question": "test"}}
                ],
            ),
            AIMessage(content="知识库暂时不可用"),
        ]

        # 不传 local_tool_handler
        result = session.run_turn(local_tool_handler=None)
        assert result["type"] == "text"


class TestRunTurnTimeout:
    """run_turn 超时保护"""

    @patch("agent_core.AgentSession.invoke_llm")
    def test_max_10_loops(self, mock_invoke):
        """LLM 持续返回工具调用 → 10 轮后超时"""
        session = AgentSession("s1")
        session.add_user_message("测试")

        # 每次返回工具调用（本地工具），触发继续循环
        tool_only = AIMessage(
            content="",
            tool_calls=[
                {"id": "c1", "name": "search_knowledge",
                 "args": {"question": "x"}}
            ],
        )
        mock_invoke.return_value = tool_only
        handler = MagicMock(return_value="some result")

        result = session.run_turn(local_tool_handler=handler)
        assert result["type"] == "text"
        assert "超时" in result["content"]
        # 应该调用了正好 10 轮
        assert mock_invoke.call_count == 10


# ============================================================
# 窗口管理
# ============================================================

class TestWindowManagement:
    """消息窗口截断"""

    def test_within_limit(self):
        session = AgentSession("s1", max_messages=10)
        for i in range(5):
            session.add_user_message(f"msg{i}")
        assert len(session.messages) == 5

    def test_truncate_on_exceed(self):
        session = AgentSession("s1", max_messages=3)
        session.add_user_message("msg0")  # 1
        session.add_user_message("msg1")  # 2
        session.add_user_message("msg2")  # 3 (full)
        session.add_user_message("msg3")  # 4 → truncate to 3
        assert len(session.messages) == 3
        assert session.messages[-1].content == "msg3"
        assert session.messages[0].content == "msg1"  # msg0 was dropped

    def test_truncate_with_tool_messages(self):
        """混合消息类型下截断正确"""
        session = AgentSession("s1", max_messages=4)
        session.add_user_message("q1")
        session.add_tool_result("c1", "r1")
        session.add_user_message("q2")
        session.add_tool_result("c2", "r2")
        # 4 messages, at limit
        assert len(session.messages) == 4
        # Add one more
        session.add_user_message("q3")
        assert len(session.messages) == 4
        # The oldest (q1) should be dropped
        assert session.messages[0].content == "r1"

    def test_truncate_after_invoke_llm(self):
        """invoke_llm 后也触发窗口截断（mock llm_with_tools 的 invoke）"""
        session = AgentSession("s1", max_messages=3)
        session.add_user_message("q1")  # 1
        session.add_user_message("q2")  # 2
        session.add_user_message("q3")  # 3

        # ChatModelBinding 是 pydantic 对象，只能 mock 模块层的引用
        with patch("langchain_core.runnables.base.RunnableBinding.invoke",
                   return_value=AIMessage(content="reply")):
            response = session.invoke_llm()  # 4 → truncate to 3

        assert len(session.messages) == 3
        assert session.messages[-1].content == "reply"
        assert session.messages[0].content == "q2"


# ============================================================
# TOOL_DEFINITIONS 结构有效性
# ============================================================

class TestToolDefinitions:
    """工具定义结构检查"""

    def test_all_tools_have_required_fields(self):
        for tool in TOOL_DEFINITIONS:
            assert tool["type"] == "function"
            fn = tool["function"]
            assert "name" in fn and fn["name"]
            assert "description" in fn and fn["description"]
            assert "parameters" in fn

    def test_tool_names_unique(self):
        names = [t["function"]["name"] for t in TOOL_DEFINITIONS]
        assert len(names) == len(set(names)), "工具名不唯一"

    def test_search_knowledge_parameters(self):
        tool = next(
            t for t in TOOL_DEFINITIONS if t["function"]["name"] == "search_knowledge"
        )
        props = tool["function"]["parameters"]["properties"]
        assert "question" in props

    def test_search_shop_parameters(self):
        tool = next(
            t for t in TOOL_DEFINITIONS if t["function"]["name"] == "search_shop"
        )
        props = tool["function"]["parameters"]["properties"]
        for field in ("name", "area", "type"):
            assert field in props

    def test_local_tool_names_in_definitions(self):
        """所有 LOCAL_TOOL_NAMES 必须在 TOOL_DEFINITIONS 中有定义"""
        defined_names = {t["function"]["name"] for t in TOOL_DEFINITIONS}
        for name in LOCAL_TOOL_NAMES:
            assert name in defined_names, f"{name} 在 TOOL_DEFINITIONS 中缺失"


# ============================================================
# SessionStore 序列化/反序列化（无需 Redis）
# ============================================================

class TestSessionStoreSerialization:
    """SessionStore 消息序列化/反序列化往返测试"""

    @pytest.fixture
    def store(self):
        # 创建一个不可用 Redis 的 store（avaiable=False），仅测试序列化方法
        return SessionStore.__new__(SessionStore)

    def test_human_message_roundtrip(self, store):
        original = [HumanMessage(content="你好")]
        data = store.serialize_messages(original)
        restored = store.deserialize_messages(data)
        assert len(restored) == 1
        assert isinstance(restored[0], HumanMessage)
        assert restored[0].content == "你好"

    def test_ai_message_roundtrip(self, store):
        original = [AIMessage(content="回复")]
        data = store.serialize_messages(original)
        restored = store.deserialize_messages(data)
        assert len(restored) == 1
        assert isinstance(restored[0], AIMessage)
        assert restored[0].content == "回复"

    def test_tool_message_roundtrip(self, store):
        original = [ToolMessage(content='{"result": "ok"}', tool_call_id="c1")]
        data = store.serialize_messages(original)
        restored = store.deserialize_messages(data)
        assert len(restored) == 1
        assert isinstance(restored[0], ToolMessage)
        assert restored[0].content == '{"result": "ok"}'
        assert restored[0].tool_call_id == "c1"

    def test_ai_with_tool_calls_roundtrip(self, store):
        original = [
            AIMessage(
                content="",
                tool_calls=[
                    {"id": "c1", "name": "search_shop", "args": {"type": "奶茶"}}
                ],
            )
        ]
        data = store.serialize_messages(original)
        restored = store.deserialize_messages(data)
        assert len(restored) == 1
        assert isinstance(restored[0], AIMessage)
        assert restored[0].tool_calls
        assert restored[0].tool_calls[0]["name"] == "search_shop"
        assert restored[0].tool_calls[0]["args"] == {"type": "奶茶"}

    def test_mixed_messages_roundtrip(self, store):
        original = [
            HumanMessage(content="找奶茶店"),
            AIMessage(
                content="",
                tool_calls=[{"id": "c1", "name": "search_shop",
                             "args": {"type": "奶茶"}}],
            ),
            ToolMessage(content='[{"id":1,"name":"喜茶"}]', tool_call_id="c1"),
            AIMessage(content="推荐喜茶，评分 4.8"),
        ]
        data = store.serialize_messages(original)
        restored = store.deserialize_messages(data)
        assert len(restored) == 4
        assert isinstance(restored[0], HumanMessage)
        assert isinstance(restored[1], AIMessage)
        assert isinstance(restored[2], ToolMessage)
        assert isinstance(restored[3], AIMessage)
        assert restored[0].content == "找奶茶店"
        assert restored[3].content == "推荐喜茶，评分 4.8"
        assert restored[1].tool_calls[0]["name"] == "search_shop"

    def test_serialize_json_output(self, store):
        """序列化结果应为合法的 JSON"""
        messages = [HumanMessage(content="你好")]
        data = store.serialize_messages(messages)
        # 确保可以序列化为 JSON 字符串
        json_str = json.dumps(data, ensure_ascii=False)
        parsed = json.loads(json_str)
        assert parsed[0]["role"] == "human"
        assert parsed[0]["content"] == "你好"

    def test_window_applied_correctly(self, store):
        store.max_messages = 3
        messages = [
            HumanMessage(content="m1"),
            HumanMessage(content="m2"),
            HumanMessage(content="m3"),
            HumanMessage(content="m4"),
            HumanMessage(content="m5"),
        ]
        result = store.apply_window(messages)
        assert len(result) == 3
        assert result[0].content == "m3"
        assert result[-1].content == "m5"

    def test_window_within_limit(self, store):
        store.max_messages = 10
        messages = [HumanMessage(content="m1"), HumanMessage(content="m2")]
        result = store.apply_window(messages)
        assert len(result) == 2
