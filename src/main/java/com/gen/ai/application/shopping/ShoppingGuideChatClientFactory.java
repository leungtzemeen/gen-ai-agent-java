package com.gen.ai.application.shopping;

import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;

import com.gen.ai.advisor.AppLoggerAdvisor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 导购场景下 {@link ChatClient} 的 Advisor 装配工厂：与 {@link AiShoppingGuideApp} 使用同一套
 * Memory + RAG + 日志链，避免 Minus 与导购各自复制 {@code defaultAdvisors(...)} 导致漂移。
 * <p>
 * 每次 {@link #buildFrozenClient(String)} 会 {@code build()} 一个新实例；Minus 任务在
 * {@link com.gen.ai.application.minus.api.MinusBrainResolver#resolve} 中只调用一次即可满足「冻结引擎」。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShoppingGuideChatClientFactory {

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor;

    /**
     * @param debugLabel 仅用于日志区分调用方（如 {@code AiShoppingGuideApp} / {@code minus:chatId}）
     */
    public ChatClient buildFrozenClient(String debugLabel) {
        ChatClient client =
                chatClientBuilder
                        .defaultAdvisors(
                                MessageChatMemoryAdvisor.builder(Objects.requireNonNull(chatMemory)).build(),
                                wiseLinkRetrievalAugmentationAdvisor,
                                new AppLoggerAdvisor())
                        .build();
        log.info(
                ">>>> [ShoppingGuide-ChatClientFactory] 已 build ChatClient label={} identityHashCode={}",
                debugLabel,
                System.identityHashCode(client));
        return client;
    }
}
