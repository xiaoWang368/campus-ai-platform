package com.campus.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.agent.AgentGrpcClient;
import com.campus.dto.Result;
import com.campus.dto.UserDTO;
import com.campus.service.IAgentService;
import com.campus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 智能问答服务实现
 * 提供 gRPC 双向流 Function Calling 模式：
 * - chat() — 通过 gRPC 双向流调用 Python Agent（Function Calling + 业务操作 + 知识检索）
 *
 * @deprecated ask() HTTP RAG 模式已废弃，由 chat() 内部集成 search_knowledge 工具替代
 */
@Slf4j
@Service
public class AgentServiceImpl implements IAgentService {

    @Value("${agent.service.url:http://localhost:8000}")
    private String agentServiceUrl;

    @Resource
    private AgentGrpcClient agentGrpcClient;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        log.info("Agent HTTP 服务地址: {}", agentServiceUrl);
    }


    @Override
    public Result chat(String question) {
        if (question == null || question.trim().isEmpty()) {
            return Result.fail("问题不能为空");
        }

        log.info("Agent gRPC chat 请求: {}", question);
        try {
            /*八位uid,通过grpc客户端与python交互
            String sessionId = UUID.randomUUID().toString().substring(0, 8);*/
            //将session_id改成记基于用户id生成的字符串
            // (原来是每次http调用都会生成一个session_id,不能实现多轮对话)
            UserDTO user = UserHolder.getUser();
            String sessionId = (user != null && user.getId() != null)
                    ? "user_" + user.getId() : "guest_" + System.currentTimeMillis();
            String answer = agentGrpcClient.chat(question.trim(), sessionId);

            log.info("Agent gRPC 回复: {}", answer.length() > 100 ? answer.substring(0, 50) + "..." : answer);
            return Result.ok(answer);
        } catch (Exception e) {
            log.error("Agent gRPC 调用失败: {}", e.getMessage(), e);
            return Result.fail("智能助手暂时不可用，请稍后再试");
        }
    }
    // ==================== HTTP RAG 模式（已废弃，由 gRPC Agent 内部集成） ====================

    // @Override
    // public Result ask(String question) {
    //     if (question == null || question.trim().isEmpty()) {
    //         return Result.fail("问题不能为空");
    //     }
    //
    //     // 设置请求头
    //     HttpHeaders headers = new HttpHeaders();
    //     headers.setContentType(MediaType.APPLICATION_JSON);
    //     headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    //
    //     // 构建请求体
    //     Map<String, String> requestBody = new HashMap<>();
    //     requestBody.put("question", question.trim());
    //
    //     try {
    //         HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);
    //
    //         log.debug("正在调用 Agent 服务: POST {}/ask", agentServiceUrl);
    //         ResponseEntity<String> response = restTemplate.exchange(
    //                 agentServiceUrl + "/ask",
    //                 HttpMethod.POST,
    //                 requestEntity,
    //                 String.class
    //         );
    //
    //         if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
    //             JsonNode jsonNode = objectMapper.readTree(response.getBody());
    //             String answer = jsonNode.get("answer").asText();
    //             return Result.ok(answer);
    //         } else {
    //             log.error("Agent 服务返回异常状态码: {}", response.getStatusCode());
    //             return Result.fail("智能问答服务暂时不可用，请稍后再试");
    //         }
    //
    //     } catch (Exception e) {
    //         log.error("调用 Agent 服务失败: {}", e.getMessage());
    //         return Result.fail("智能问答服务暂时不可用，请稍后再试");
    //     }
    // }
}



