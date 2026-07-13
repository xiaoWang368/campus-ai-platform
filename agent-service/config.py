"""
配置管理模块
从 .env 文件和环境变量中加载配置
"""
from pydantic_settings import BaseSettings

#! .venv/Scripts/python agent-service/data_loader.py
class Settings(BaseSettings):
    # 阿里云百炼 DashScope（OpenAI 兼容格式）
    dashscope_api_key: str
    dashscope_api_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_model: str = "glm-5"
    embedding_model: str = "text-embedding-v2"

    # MySQL
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = "1234"
    mysql_database: str = "hmdp"

    # Redis（Agent 会话持久化）
    redis_url: str = "redis://localhost:6379/0"

    # ChromaDB
    chroma_persist_dir: str = "../agent-service/chroma_db"

    # Server
    host: str = "0.0.0.0"
    port: int = 8000

    class Config:
        env_file = "../agent-service/.env"
        env_file_encoding = "utf-8"


settings = Settings()
