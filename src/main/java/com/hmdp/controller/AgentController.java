package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Agent 智能服务控制器
 * 提供以下接口：
 * - POST /agent/chat    — 智能助手（gRPC Agent），支持自然语言查店、查券、写笔记、知识问答等
 * - GET  /agent/health  — 健康检查
 * - POST /agent/ingest  — 重新加载数据
 *
 * @deprecated RAG 知识问答已集成到 /agent/chat 中，不再需要单独的 /agent/ask 接口
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Resource
    private IAgentService agentService;


    /**
     * 智能助手（gRPC Agent 模式）
     * 通过 gRPC 双向流调用 Python Agent，支持：
     * - 自然语言搜索店铺
     * - 查询优惠券
     * - 创建探店笔记
     * - 多步任务编排
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        log.info("收到智能助手请求: {}", question);
        return agentService.chat(question);
    }


}
