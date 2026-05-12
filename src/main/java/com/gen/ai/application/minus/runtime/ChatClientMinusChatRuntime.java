package com.gen.ai.application.minus.runtime;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;

import com.gen.ai.application.minus.api.MinusChatRuntime;
import com.gen.ai.application.minus.policy.RagParticipationPolicy;

/**
 * Phase 3：同时冻结「带 RAG」与「不带 RAG」两套 {@link ChatClient}（同一底座模型），由
 * {@link RagParticipationPolicy} 按外层步号择一，避免第 2 步起重复检索。
 */
public final class ChatClientMinusChatRuntime implements MinusChatRuntime {

    private final ChatClient chatClientWithRag;
    private final ChatClient chatClientWithoutRag;
    private final String debugId;

    public ChatClientMinusChatRuntime(
            ChatClient chatClientWithRag, ChatClient chatClientWithoutRag, String debugId) {
        this.chatClientWithRag = chatClientWithRag;
        this.chatClientWithoutRag = chatClientWithoutRag;
        this.debugId = debugId;
    }

    @Override
    public String runtimeDebugId() {
        return debugId;
    }

    /**
     * 兼容 Phase 2 诊断：返回带 RAG 实例；多步是否同一物理 client 见 {@link #selectForStep(int, RagParticipationPolicy)}。
     */
    @Override
    public Optional<ChatClient> frozenChatClient() {
        return Optional.of(chatClientWithRag);
    }

    public ChatClient selectForStep(int stepIndex, RagParticipationPolicy policy) {
        return policy.useRag(stepIndex) ? chatClientWithRag : chatClientWithoutRag;
    }
}
