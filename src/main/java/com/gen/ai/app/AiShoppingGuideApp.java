package com.gen.ai.app;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AiShoppingGuideApp {

    private final ChatClient chatClient;

    private final Resource systemResource;

    public AiShoppingGuideApp(
            DashScopeChatModel dashScopeChatModel,
            @Value("classpath:/prompts/assistant-guide.st") Resource systemResource) {
        this.systemResource = systemResource;

        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
        this.chatClient = ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String doChat(String message, String chatId) {
        String dynamicSystem = new SystemPromptTemplate(systemResource)
                .createMessage(Map.of("current_date", LocalDate.now().toString()))
                .getText();
        ChatResponse response = chatClient
                .prompt()
                .system(dynamicSystem)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

}
