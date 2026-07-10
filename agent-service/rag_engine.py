"""
RAG 检索引擎
负责：检索相似文档 → 构建 Prompt → 调用 GLM-5 → 返回答案
"""
import logging
from typing import Optional

from langchain_openai import ChatOpenAI
from langchain_community.vectorstores import Chroma
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables import RunnablePassthrough, RunnableSequence
from langchain_core.documents import Document
from langchain_core.messages import HumanMessage, AIMessage

from config import settings
from data_loader import DataLoader

logger = logging.getLogger(__name__)

# 系统提示词
SYSTEM_PROMPT = """你是一个智能的店铺推荐助手，帮助用户找到合适的店铺和优惠信息。

你可以回答关于店铺信息、店铺推荐、价格、营业时间、优惠券等方面的问题。
请基于提供的上下文信息回答问题。如果上下文信息不足，请如实告知用户你不知道，不要编造信息。

回答要求：
1. 回答要简洁、准确、友好，使用中文
2. 如果有多个推荐，用编号列出
3. 适当结合店铺评分、价格等信息给用户建议
4. 如果用户的问题不在你的知识范围内，礼貌告知无法回答"""


class RAGEngine:
    """RAG 检索与生成引擎"""

    def __init__(self):
        # 初始化 LLM（通过阿里云百炼调用 GLM-5，OpenAI 兼容格式）
        self.llm = ChatOpenAI(
            model=settings.llm_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_api_base_url,
            temperature=0.5,
            max_tokens=1024,
        )
        # 数据加载器
        self.data_loader: Optional[DataLoader] = None
        # 向量库
        self.vector_store: Optional[Chroma] = None
        # 对话记忆（保留最近 3 轮对话）
        self._history: list[tuple[str, str]] = []
        # RAG Chain
        self._chain: Optional[RunnableSequence] = None


    def initialize(self):
        """初始化引擎：加载或构建向量库"""
        loader = DataLoader()
        self.data_loader = loader

        try:
            # 尝试加载已有的向量库
            self.vector_store = loader.load_vector_store()
            # 检查是否有数据
            count = self.vector_store._collection.count()
            if count == 0:
                logger.info("向量库为空，重新构建...")
                self.vector_store = loader.build_vector_store()
            else:
                logger.info("已加载向量库，共 %d 个向量", count)
        except Exception as e:
            logger.warning("加载向量库失败，重新构建: %s", e)
            self.vector_store = loader.build_vector_store()

        # 构建 RAG Chain
        self._build_chain()

    def _build_chain(self):
        """构建 RAG 处理链"""
        prompt = ChatPromptTemplate.from_messages([
            ("system", SYSTEM_PROMPT),
            MessagesPlaceholder(variable_name="history"),
            ("human", "以下是相关的参考信息：\n\n{context}\n\n---\n\n用户问题：{question}"),
        ])
        # 构建 Chain
        self._chain = (
            RunnablePassthrough.assign(
                context=lambda x: self._retrieve(x["question"])
            )
            | prompt
            | self.llm
        )


    def _retrieve(self, question: str) -> str:
        """检索相关文档并格式化为上下文"""
        #最大距离,超过这个距离的舍弃
        MAX_DISTANCE = 0.3
        if self.vector_store is None:
            return ""
        docs_with_scores = self.vector_store.similarity_search_with_score(question, k=5)
        formatted = []
        seen = set()
        for doc, score in docs_with_scores:
            key = doc.page_content[:50]
            if key in seen:
                continue
            seen.add(key)
            # 过滤距离过大的结果（距离越小越相似）
            if score > MAX_DISTANCE:
                continue
            formatted.append(doc.page_content)
            logger.debug("检索命中[距离: %.3f] %s", score, key)
        return "\n\n".join(formatted)

    def ask(self, question: str) -> tuple[str, list[dict]]:
        """问答接口
        Args:
            question: 用户问题
        Returns:
            (answer_text, source_list) 元组
        """
        if self._chain is None:
            logger.warning("RAG Chain 未初始化，尝试重新初始化...")
            self.initialize()    #初始化了chain,self.vector_store(向量数据库)

        # 获取历史对话
        history = []
        logger.info("用户提问: %s", question)
        try:
            # 执行 RAG Chain
            response = self._chain.invoke({
                "question": question,
                "history": history,
            })
            answer = response.content
            # 检索来源信息（用于返回给前端）
            if self.vector_store:
                docs = self.vector_store.similarity_search(question, k=3)
                #根据data_loader所含有的key,来确定这里面需要传入什么参数
                sources = [
                    {
                        "type": doc.metadata.get("type", "unknown"),
                        "name": doc.metadata.get("name") or doc.metadata.get("title", ""),
                        "content_preview": doc.page_content[:100],
                    }
                    for doc in docs
                ]
            else:
                sources = []
            logger.info(f"ai回答:{answer}")
            logger.info("回答完成 (长度: %d)", len(answer))
            return answer, sources

        except Exception as e:
            logger.error("问答处理异常: %s", e, exc_info=True)
            raise

    def rebuild_vector_store(self) -> int:
        """重建向量库（重新加载所有数据并生成向量）"""
        if self.data_loader is None:
            self.data_loader = DataLoader()
        self.vector_store = self.data_loader.build_vector_store()
        # 重新构建 chain
        self._build_chain()
        # 返回向量数量
        return len(self.vector_store)

    def get_vector_count(self) -> int:
        """获取向量库中的文档数量"""
        if self.vector_store is None:
            return 0
        return len(self.vector_store)

    def get_collections(self) -> list[str]:
        """获取向量库中的集合列表"""
        if self.vector_store is None:
            return []
        return [self.vector_store._collection.name]

    def close(self):
        """释放资源"""
        if self.data_loader:
            self.data_loader.close()
