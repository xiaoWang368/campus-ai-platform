package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * Agent 智能问答服务接口
 * 提供以下模式：
 * - chat(): 通过 gRPC 双向流调用 Python Agent，支持 Function Calling + 知识检索
 *
 * @deprecated ask() HTTP RAG 模式已废弃，由 chat() 内部集成 search_knowledge 工具替代
 */
public interface IAgentService {

    /**
     * 向 RAG 服务提问（HTTP 模式，知识问答）- 已废弃
     *
     * @param question 用户问题
     * @return 智能回答结果
     */
    // Result ask(String question);

    /**
     * 向 Agent 发送消息（gRPC 双向流模式，支持工具调用 + 知识检索）
     *
     * @param question 用户问题
     * @return Agent 回复
     */
    Result chat(String question);

}
