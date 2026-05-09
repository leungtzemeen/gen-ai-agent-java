package com.gen.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class WiseLinkBrainPlugConfig {

    // --- 模块 A：本地 Ollama ---
    @Bean
    @Primary  // <--- 只要我被激活，我就是主大脑
    @ConditionalOnProperty(prefix = "wiselink", name = "active-brain", havingValue = "ollama")
    public ChatModel brainOllama(OllamaChatModel ollamaChatModel) {
        log.info(">>>> [BRAIN-PLUGGED] 已物理接入【本地 Ollama】，当前为 0 成本运行模式。");
        return ollamaChatModel;
    }

    // --- 模块 B：阿里百炼 ---
    @Bean
    @Primary  // <--- 只要我被激活，我就是主大脑
    @ConditionalOnProperty(prefix = "wiselink", name = "active-brain", havingValue = "dashscope")
    public ChatModel brainDashScope(DashScopeChatModel dashScopeChatModel) {
        log.info(">>>> [BRAIN-PLUGGED] 已物理接入【阿里百炼】，请注意云端 Token 消耗。");
        return dashScopeChatModel;
    }

    // --- 模块 C：DeepSeek ---
    @Bean
    @Primary  // <--- 只要我被激活，我就是主大脑
    @ConditionalOnProperty(prefix = "wiselink", name = "active-brain", havingValue = "deepseek")
    public ChatModel brainDeepSeek(OpenAiChatModel openAiChatModel) {
        log.info(">>>> [BRAIN-PLUGGED] 已物理接入【DeepSeek】，享受极致推理性价比。");
        return openAiChatModel;
    }

}
