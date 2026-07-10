"""
数据加载器
从 MySQL 加载店铺、店铺类型、博客、优惠券数据，
使用阿里云百炼 Embedding API（OpenAI 兼容格式）生成向量并存入 ChromaDB。
"""
import logging
from typing import Optional

import pymysql
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import Chroma

from config import settings

logger = logging.getLogger(__name__)


class DataLoader:
    """数据加载器：MySQL → Document → Embedding → ChromaDB"""

    def __init__(self):
        self.embeddings = DashScopeEmbeddings(
            model=settings.embedding_model,  #加载已有的向量数据库
            dashscope_api_key=settings.dashscope_api_key,
        )
        self.db_conn: Optional[pymysql.Connection] = None
        self._connect_db()

    def _connect_db(self):
        """连接 MySQL 数据库"""
        try:
            self.db_conn = pymysql.connect(
                host=settings.mysql_host,
                port=settings.mysql_port,
                user=settings.mysql_user,
                password=settings.mysql_password,
                database=settings.mysql_database,
                charset="utf8mb4",
                cursorclass=pymysql.cursors.DictCursor,
            )
            logger.info("MySQL 连接成功: %s:%s/%s",
                        settings.mysql_host, settings.mysql_port, settings.mysql_database)
        except Exception as e:
            logger.error("MySQL 连接失败: %s", e)
            raise

    def _query_all(self, sql: str) -> list[dict]:
        """执行 SQL 查询，返回所有结果"""
        try:
            # 创建游标并执行查询
            with self.db_conn.cursor() as cursor:
                cursor.execute(sql)
                return cursor.fetchall()
        except pymysql.MySQLError as e:
            logger.error("sql执行失败: %s | sql: %s", e, sql[:100])
            raise

    def load_shops(self) -> list[Document]:
        """加载店铺数据"""
        rows = self._query_all("""
            SELECT s.id, s.name, s.area, s.address, s.avg_price, s.score,
                   s.open_hours, s.sold, s.comments, t.name as type_name
            FROM tb_shop s
            LEFT JOIN tb_shop_type t ON s.type_id = t.id
        """)
        documents = []
        for row in rows:
            content = (
                f"店铺名称：{row['name']} | "
                f"分类：{row['type_name']} | "
                f"区域：{row['area']} | "
                f"地址：{row['address']} | "
                f"均价：{row['avg_price']}元 | "
                f"评分：{round(row['score'] / 10, 1)}分 | "
                f"营业时间：{row['open_hours']} | "
                f"已售：{row['sold']} | "
                f"评论数：{row['comments']}"
            )
            documents.append(Document(
                page_content=content,
                metadata={"type": "shop", "id": row["id"], "name": row["name"]}
            ))
        logger.info("加载 %d 条店铺数据", len(documents))
        return documents

    def load_shop_types(self) -> list[Document]:
        """加载店铺分类数据"""
        rows = self._query_all("SELECT id, name, sort FROM tb_shop_type ORDER BY sort")
        documents = []
        for row in rows:
            content = f"店铺分类：{row['name']} | 排序：{row['sort']}"
            documents.append(Document(
                page_content=content,
                metadata={"type": "shop_type", "id": row["id"], "name": row["name"]}
            ))
        logger.info("加载 %d 条店铺分类数据", len(documents))
        return documents

    def load_blogs(self) -> list[Document]:
        """加载博客笔记数据"""
        rows = self._query_all("""
            SELECT b.id, b.title, b.content, b.shop_id, s.name as shop_name
            FROM tb_blog b
            LEFT JOIN tb_shop s ON b.shop_id = s.id
        """)
        documents = []
        for row in rows:
            shop_info = f"（相关店铺：{row['shop_name']}）" if row.get("shop_name") else ""
            content = (
                f"笔记标题：{row['title']}\n"
                f"笔记内容：{row['content']}\n"
                f"{shop_info}"
            )
            documents.append(Document(
                page_content=content,
                metadata={
                    "type": "blog", "id": row["id"],
                    "title": row["title"],
                    "shop_id": row.get("shop_id"),
                }
            ))
        logger.info("加载 %d 条博客笔记数据", len(documents))
        return documents

    def load_vouchers(self) -> list[Document]:
        """加载优惠券数据"""
        rows = self._query_all("""
            SELECT v.id, v.title, v.sub_title, v.rules, v.pay_value, v.actual_value,
                   v.type, s.name as shop_name
            FROM tb_voucher v
            LEFT JOIN tb_shop s ON v.shop_id = s.id
        """)
        documents = []
        voucher_type_map = {0: "普通券",
                            1: "秒杀券"}
        for row in rows:
            content = (
                f"优惠券名称：{row['title']} | "
                f"副标题：{row['sub_title'] or ''} | "
                f"类型：{voucher_type_map.get(row['type'], '未知')} | "
                f"支付金额：{row['pay_value']}元 | "
                f"实际价值：{row['actual_value']}元 | "
                f"使用规则：{row['rules'] or '无'} | "
                f"适用店铺：{row['shop_name'] or '全部店铺'}"
            )
            documents.append(Document(
                page_content=content,
                metadata={"type": "voucher", "id": row["id"], "title": row["title"]}
            ))
        logger.info("加载 %d 条优惠券数据", len(documents))
        return documents

    def build_vector_store(self) -> Chroma:
        """构建向量数据库"""
        # 加载所有数据
        all_documents = []
        try:
            all_documents.extend(self.load_shop_types())
            all_documents.extend(self.load_shops())
            all_documents.extend(self.load_blogs())
            all_documents.extend(self.load_vouchers())
        except Exception as e:
            logger.error("数据加载失败: %s",e)
            raise

        if not all_documents:
            logger.warning("没有加载到任何数据，向量库为空")
            return Chroma(
                embedding_function=self.embeddings,
                persist_directory=settings.chroma_persist_dir,
            )

        # 文本分割（对较长的博客内容进行切分）
        text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=500,
            chunk_overlap=50,
            separators=["\n", "。", "；", "，", " ", ""],
        )
        split_docs = text_splitter.split_documents(all_documents)
        try:
            logger.info("开始构建向量库，共 %d 个文档块...", len(split_docs))
            # 创建向量库
            vector_store = Chroma.from_documents(
                documents=split_docs,   #切割字符串
                embedding=self.embeddings,   #向量数据库
                persist_directory=settings.chroma_persist_dir,   #持久化路径
            )
            vector_store.persist()

            logger.info("向量库构建完成，共 %d 个向量", len(split_docs))
            return vector_store
        except Exception as e:
            logger.error("向量数据库构建失败: %s", e)
            raise


    def load_vector_store(self) -> Chroma:
        """加载已有的向量数据库"""
        return Chroma(
            embedding_function=self.embeddings,
            persist_directory=settings.chroma_persist_dir,
        )

    def close(self):
        """关闭数据库连接"""
        if self.db_conn:
            self.db_conn.close()
            logger.info("MySQL 连接已关闭")
