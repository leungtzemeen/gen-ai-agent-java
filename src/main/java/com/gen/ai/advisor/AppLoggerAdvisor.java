package com.gen.ai.advisor;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 在 ChatClient 调用链前后打印用户输入与模型输出，便于排查 RAG 与工具调用问题。
 */
@Slf4j
public class AppLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        logRequest(chatClientRequest);

        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

        logResponse(chatClientResponse);

        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        logRequest(chatClientRequest);

        Flux<ChatClientResponse> chatClientResponses = streamAdvisorChain.nextStream(chatClientRequest);

        return new ChatClientMessageAggregator().aggregateChatClientResponse(chatClientResponses, this::logResponse);
    }

    private void logRequest(ChatClientRequest request) {
        // 1. 请求前记录
        String userText = request.prompt() != null && request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText()
                : "";
        log.info(">>>> [AI Request] content: {}", userText);
    }

    private void logResponse(ChatClientResponse chatClientResponse) {
        // 3. 响应后记录
        ChatResponse response = chatClientResponse.chatResponse();
        String aiText = response != null
                && response.getResult() != null
                && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText()
                        : "";
        log.info("<<<< [AI Response] content: {}", aiText);
    }

}
