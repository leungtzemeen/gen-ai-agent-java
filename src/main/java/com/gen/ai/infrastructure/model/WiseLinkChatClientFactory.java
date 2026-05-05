package com.gen.ai.infrastructure.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.gen.ai.advisor.AppLoggerAdvisor;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 按 {@link WiseLinkLlmProfile} 选择底层对话模型并装配默认 Advisors（记忆、RAG、请求日志）。
 * <ul>
 *   <li>{@link WiseLinkLlmProfile#QWEN} → {@link DashScopeChatModel}（百炼）</li>
 *   <li>{@link WiseLinkLlmProfile#DEEPSEEK} → {@link OpenAiChatModel}（spring.ai.openai / DeepSeek 兼容端点），<b>不得</b>使用 DashScope</li>
 * </ul>
 */
@Component
@Slf4j
public class WiseLinkChatClientFactory {

    private final Map<WiseLinkLlmProfile, ChatClient> clients = new EnumMap<>(WiseLinkLlmProfile.class);

    public WiseLinkChatClientFactory(
            DashScopeChatModel dashScopeChatModel,
            OpenAiChatModel openAiChatModel,
            ChatMemory chatMemory,
            RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor) {
        Objects.requireNonNull(chatMemory, "chatMemory");
        DashScopeChatModel qwen = Objects.requireNonNull(dashScopeChatModel, "DashScopeChatModel（Qwen）");
        OpenAiChatModel deepSeek = Objects.requireNonNull(openAiChatModel, "OpenAiChatModel（DeepSeek / spring.ai.openai）");
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        var loggerAdvisor = new AppLoggerAdvisor();
        this.clients.put(
                WiseLinkLlmProfile.QWEN,
                ChatClient.builder(qwen)
                        .defaultAdvisors(memoryAdvisor, wiseLinkRetrievalAugmentationAdvisor, loggerAdvisor)
                        .build());
        // DeepSeek 通道：仅绑定 OpenAiChatModel，与 DashScope 完全隔离
        this.clients.put(
                WiseLinkLlmProfile.DEEPSEEK,
                ChatClient.builder(deepSeek)
                        .defaultAdvisors(memoryAdvisor, wiseLinkRetrievalAugmentationAdvisor, loggerAdvisor)
                        .build());
        log.info(
                ">>>> [WiseLink-Model] ChatClient 装配：QWEN→{}，DEEPSEEK→{}",
                qwen.getClass().getSimpleName(),
                deepSeek.getClass().getSimpleName());
    }

    public ChatClient chatClient(WiseLinkLlmProfile profile) {
        ChatClient c = clients.get(profile == null ? WiseLinkLlmProfile.QWEN : profile);
        return c != null ? c : clients.get(WiseLinkLlmProfile.QWEN);
    }

    @PostConstruct
    void logDeepSeekChannelReady() {
        if (clients.containsKey(WiseLinkLlmProfile.DEEPSEEK)) {
            log.info(">>>> [WiseLink-Model] 成功接入 DeepSeek 通道");
        }
    }
}
