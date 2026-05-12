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
 * 每次 {@link #buildFrozenClient(String)} / {@link #buildFrozenClientWithoutRag(String)} 会 {@code build()}
 * 一个新实例；Minus 任务在 {@link com.gen.ai.application.minus.api.MinusBrainResolver#resolve} 中成对构建后，
 * 由 {@link com.gen.ai.application.minus.policy.RagParticipationPolicy} 在每一步择一使用。
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

    /**
     * 与 {@link #buildFrozenClient(String)} 共用同一 {@link org.springframework.ai.chat.model.ChatModel} 底座，
     * 但不挂载 {@link RetrievalAugmentationAdvisor}，供 Minus 外层第 2 步起避免重复 RAG。
     */
    public ChatClient buildFrozenClientWithoutRag(String debugLabel) {
        ChatClient client =
                chatClientBuilder
                        .defaultAdvisors(
                                MessageChatMemoryAdvisor.builder(Objects.requireNonNull(chatMemory)).build(),
                                new AppLoggerAdvisor())
                        .build();
        log.info(
                ">>>> [ShoppingGuide-ChatClientFactory] 已 build ChatClient（无 RAG）label={} identityHashCode={}",
                debugLabel,
                System.identityHashCode(client));
        return client;
    }
}
