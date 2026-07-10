package com.hmdp.agent;

import com.hmdp.agent.AgentServiceGrpc.AgentServiceStub;
import com.hmdp.agent.Agent.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC 双向流客户端
 * 与 Python Agent 服务进行双向流通信：
 * 1. 发送用户问题
 * 2. 接收 ToolCall 请求 → 通过 ToolExecutor 执行业务 → 回传 ToolResult
 * 3. 接收最终文本回复
 */
@Slf4j
@Component
public class AgentGrpcClient implements DisposableBean {

    private final ManagedChannel channel;
    private final AgentServiceStub asyncStub;

    @Resource
    private ToolExecutor toolExecutor;

    public AgentGrpcClient(
            @Value("${agent.grpc.host:localhost}") String host,
            @Value("${agent.grpc.port:50051}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        this.asyncStub = AgentServiceGrpc.newStub(channel);
        log.info("gRPC 客户端已初始化, 连接目标: {}:{}", host, port);
    }

    /**
     * 向 Agent 发送消息并获取回复（同步接口，底层使用异步 gRPC）
     *
     * @param question  用户问题
     * @param sessionId 会话 ID（可用于多轮对话）
     * @return Agent 的文本回复
     */
    public String chat(String question, String sessionId) {
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        AtomicReference<StreamObserver<ClientMessage>> requestObserverRef = new AtomicReference<>();
        //首先接收python发来的服务端请求,携带servermessage,
        // 判断是1,工具调用(toolexecutor.execute) 2,消息回复(responseFuture.complete)
        //通过预先生成的requestObserverRef.get获得客户端中的消息,回调执行结果给agent模型,反馈

        // 响应观察者：接收 Python 端发来的消息
        StreamObserver<ServerMessage> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage msg) {
                if (msg.hasToolCall()) {
                    // --- Agent 请求执行 Tool ---  agent决定调用什么工具
                    ToolCall tc = msg.getToolCall();
                    log.info("收到 ToolCall: {}[{}] args={}",
                            tc.getToolName(), tc.getCallId(), tc.getArguments());

                    try {
                        // 通过 ToolExecutor 执行业务
                        String result = toolExecutor.execute(tc.getToolName(), tc.getArguments());
                        //封装toolresult,给clientmessage使用
                        ToolResult toolResult = ToolResult.newBuilder()
                                .setCallId(tc.getCallId())
                                .setResult(result)
                                .build();
                        // 回传执行结果给 Python Agent
                        requestObserverRef.get().onNext(   //onNext: 往流里发送一条消息给python端
                                ClientMessage.newBuilder()
                                        .setSessionId(sessionId)
                                        .setToolResult(toolResult)
                                        .build());
                        log.info("Tool 结果已回传: {}[{}]", tc.getToolName(), tc.getCallId());
                    } catch (Exception e) {
                        log.error("Tool 执行失败: {}", e.getMessage(), e);
                        // 回传错误信息
                        ToolResult errorResult = ToolResult.newBuilder()
                                .setCallId(tc.getCallId())
                                .setResult("{\"error\":\"" + e.getMessage() + "\"}")
                                .build();
                        requestObserverRef.get().onNext(
                                ClientMessage.newBuilder()
                                        .setSessionId(sessionId)
                                        .setToolResult(errorResult)
                                        .build());
                    }

                } else if (msg.hasText()) {
                    // --- 最终文本回复 ---
                    log.info("收到 Agent 回复: {}", msg.getText().substring(0, Math.min(msg.getText().length(), 80)));
                    responseFuture.complete(msg.getText());

                } else if (msg.hasError()) {
                    // --- 错误信息 ---
                    log.error("Agent 返回错误: [{}] {}", msg.getError().getCode(), msg.getError().getMessage());
                    responseFuture.completeExceptionally(
                            new RuntimeException("Agent 错误: " + msg.getError().getMessage()));
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("gRPC 流错误: {}", t.getMessage(), t);
                responseFuture.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                log.info("gRPC 流已关闭");
                if (!responseFuture.isDone()) {
                    responseFuture.completeExceptionally(
                            new RuntimeException("Agent 流意外关闭"));
                }
            }
        };

        // 打开双向流，获取请求观察者
        StreamObserver<ClientMessage> requestObserver = asyncStub.chat(responseObserver);
        requestObserverRef.set(requestObserver);

        // 发送用户消息
        ClientMessage userMsg = ClientMessage.newBuilder()
                .setSessionId(sessionId)
                .setUserMessage(question)
                .build();
        requestObserver.onNext(userMsg);
        log.info("已发送用户消息到 Agent (session={})", sessionId);

        // 等待回复（最多 60 秒，防止 Agent 卡住）
        try {
            String result = responseFuture.get(60, TimeUnit.SECONDS);
            return result;
        } catch (TimeoutException e) {
            log.error("Agent 回复超时");
            requestObserver.onError(e);
            return "抱歉，请求超时，请稍后再试";
        } catch (Exception e) {
            log.error("Agent 通信失败: {}", e.getMessage());
            return "抱歉，服务暂时不可用，请稍后再试";
        } finally {
            // 关闭流
            try {
                requestObserver.onCompleted();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 简化调用（使用默认 session）
     */
    public String chat(String question) {
        return chat(question, "default");
    }

    @Override
    public void destroy() {
        log.info("关闭 gRPC 通道...");
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
        log.info("gRPC 通道已关闭");
    }
}
