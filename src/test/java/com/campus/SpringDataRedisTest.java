package com.campus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SpringBootTest
public class SpringDataRedisTest {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Test
public void testRedis(){
    //写入一条数据
    stringRedisTemplate.opsForHash().put("100","name","jichen");
/*stringRedisTemplate.opsForHash().put("100","age","19");*/
        System.out.println(stringRedisTemplate.opsForHash().get("100","name"));

    }
}
/*
* ShopServiceImpl.java（商铺查询）
    ├── queryById()
    │   └── cacheClient.queryWithLogicalExpire()  ← 当前生效：逻辑过期解决击穿
    │
    ├── queryWithPassThrough()  [注释]  ← 手写版：缓存空值解决穿透
    ├── queryWithMutex()        [注释]  ← 手写版：互斥锁解决击穿
    └── queryWithLogicalExpire()[注释]  ← 手写版：逻辑过期解决击穿

CacheClient.java（通用缓存工具）
    ├── queryWithPassThrough()  ← 通用封装：缓存空值解决穿透
    └── queryWithLogicalExpire()← 通用封装：逻辑过期解决击穿

VoucherOrderServiceImpl.java（秒杀下单）
    ├── Lua 脚本               ← 原子操作解决并发（库存超卖）
    └── Redisson 分布式锁       ← 兜底防重复下单
*/
/* ┌─ 用户请求 ─────────────────────────────────────────────────┐
  │                                                            │
  │   POST /agent/ask     → HTTP RAG（知识问答，原有保留）         │
  │   POST /agent/chat    → gRPC Agent（查店/查券/写笔记，新增）   │
  │                                                            │
  └────────────────────────┬───────────────────────────────────┘
                           │
  ┌────────────────────────▼───────────────────────────────────┐
  │  Spring Boot (Java)                                        │
  │                                                            │
  │  AgentController → AgentServiceImpl                        │
  │                        ├── ask() → HTTP → Python FastAPI   │
  │                        └── chat() → gRPC 双向流             │
  │                                      ↓                     │
  │                            AgentGrpcClient                 │
  │                              ↓        ↑                    │
  │                            ToolCall   ToolResult           │
  │                              ↓        ↑                    │
  │                            ToolExecutor                    │
  │                              ├── ShopService.search        │
  │                              ├── VoucherService.query      │
  │                              └── BlogService.create        │
  └────────────────────────┬───────────────────────────────────┘
                           │ gRPC 双向流 (localhost:50051)
  ┌────────────────────────▼───────────────────────────────────┐
  │  Python Agent (同进程，新增 gRPC 服务)                       │
  │                                                            │
  │  FastAPI (HTTP:8000)    ← RAG（原有）                       │
  │  gRPC Server (50051)    ← Agent（新增）                     │
  │                                                            │
  │  AgentGrpcServicer.Chat()                                   │
  │    ↓ 收到用户消息 → LLM Function Calling                    │
  │    ↓ 判断需要工具 → 发 ToolCall → 等 ToolResult              │
  │    ↓ 拿到结果 → 继续 LLM → 回 text                         │
  └────────────────────────────────────────────────────────────┘

  新增文件清单

  ┌────────────────────────────────────────────────┬──────────────────────────────────────────────────────────────────┐
  │                      文件                      │                               作用                               │
  ├────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────┤
  │ proto/agent.proto                              │ gRPC 服务定义（Chat 双向流 RPC）                                 │
  ├────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────┤
  │ agent/AgentGrpcClient.java                     │ Java gRPC 客户端（发送消息 → 接收 ToolCall → 回传结果 → 拿回复） │
  ├────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────┤
  │ agent/ToolExecutor.java                        │ Tool 执行器（search_shop / query_voucher / create_blog）         │
  ├────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────┤
  │ agent-service/grpc_server.py                   │ Python gRPC 服务器（LLM Function Calling + 工具编排）            │
  ├────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────┤
  │ agent-service/agent_pb2.py / agent_pb2_grpc.py │ Python gRPC stubs（自动生成）                                    │
  └────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────┘

  修改文件清单

  ┌────────────────────────────────┬────────────────────────────────────────────────────┐
  │              文件              │                        改动                        │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ pom.xml                        │ 加 gRPC 依赖 + protobuf 插件 + Lombok 1.18.30      │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ application.yaml               │ 加 agent.grpc 配置（host/port）                    │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ AgentController.java           │ 加 POST /agent/chat 端点                           │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ IAgentService.java             │ 加 chat() 方法                                     │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ AgentServiceImpl.java          │ 加 chat() 实现（gRPC），保留原有 ask()（HTTP RAG） │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ SystemConstants.java           │ 加 AGENT_USER_ID                                   │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ agent-service/main.py          │ 后台启动 gRPC 服务                                 │
  ├────────────────────────────────┼────────────────────────────────────────────────────┤
  │ agent-service/requirements.txt │ 加 grpcio                                          │
  └────────────────────────────────┴────────────────────────────────────────────────────┘

  三个业务场景的端到端流程

  场景 1：自然语言搜店
  用户："推荐朝阳区评分高的奶茶店"
    → Java 发 gRPC → Python LLM 解析意图 → ToolCall search_shop(name="", area="朝阳区", type="奶茶")
    → Java 执行: ShopService.query().like("area","朝阳区").eq("type_id", 奶茶的typeId).orderByDesc("score")
    → 返回 JSON 结果 → Python 组织自然语言 → "为您找到以下店铺..."

  场景 2：查优惠券
  用户："喜茶有什么优惠券"
    → Python LLM → ToolCall query_voucher_of_shop(shop_name="喜茶")
    → Java: 查 Shop → 调 VoucherService.queryVoucherOfShop(shopId) → 返回优惠券列表
    → Python: "喜茶目前有以下优惠券：1. 10元代金券 2. 满100减20..."

  场景 3：代写笔记
  用户："帮我在喜茶发一篇笔记，标题是'好喝'，内容写'环境不错'"
    → Python LLM → ToolCall create_blog(shop_id=1, title="好喝", content="环境不错")
    → Java: BlogService.saveBlog → 写入 MySQL + Redis 推送 → 返回 blogId
    → Python: "笔记发布成功！"

  启动方式

  # 1. 启动 Python 服务（HTTP + gRPC 同进程）
  cd agent-service && uvicorn main:app --host 0.0.0.0 --port 8000

  # 2. 启动 Spring Boot
  mvn spring-boot:run

  # 3. 测试
  curl -X POST http://localhost:8081/agent/chat \
    -H "Content-Type: application/json" \
    -d '{"question":"推荐朝阳区的奶茶店"}'
*
* */
