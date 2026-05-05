package com.gen.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;

/**
 * 多模型共存时，RAG 压缩/扩展与默认 {@link org.springframework.ai.chat.client.ChatClient.Builder} 仍走百炼 Qwen。
 */
@Configuration
public class WiseLinkPrimaryChatModelConfig {

    @Bean
    @Primary
    public ChatModel wiseLinkPrimaryChatModel(DashScopeChatModel dashScopeChatModel) {
        return dashScopeChatModel;
    }
}
