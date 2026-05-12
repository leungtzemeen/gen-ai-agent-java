package com.gen.ai.application.minus.runtime;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;

import com.gen.ai.application.minus.api.MinusChatRuntime;

/**
 * Phase 2：携带由 {@link com.gen.ai.application.shopping.ShoppingGuideChatClientFactory} 构建并冻结的
 * {@link ChatClient}，供后续 {@code MinusStepExecutor} 多步复用（同一引用）。
 */
public final class ChatClientMinusChatRuntime implements MinusChatRuntime {

    private final ChatClient chatClient;
    private final String debugId;

    public ChatClientMinusChatRuntime(ChatClient chatClient, String debugId) {
        this.chatClient = chatClient;
        this.debugId = debugId;
    }

    @Override
    public String runtimeDebugId() {
        return debugId;
    }

    @Override
    public Optional<ChatClient> frozenChatClient() {
        return Optional.of(chatClient);
    }

    /** 诊断用：确认多步是否同一实例。 */
    public ChatClient client() {
        return chatClient;
    }
}
