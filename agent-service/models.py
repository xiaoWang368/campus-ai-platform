"""
Pydantic 数据模型
定义 API 请求和响应的数据结构
"""
from pydantic import BaseModel


class AskRequest(BaseModel):
    """问答请求"""
    question: str


class AskResponse(BaseModel):
    """问答响应"""
    answer: str
    sources: list[dict] = []


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str
    vector_count: int = 0
    collections: list[str] = []


class IngestResponse(BaseModel):
    """数据加载响应"""
    success: bool
    total_documents: int
    collections: dict[str, int]
