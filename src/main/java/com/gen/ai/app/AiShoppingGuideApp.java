package com.gen.ai.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.gen.ai.advisor.AppLoggerAdvisor;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AiShoppingGuideApp {

        private final ChatClient chatClient;

        private final Resource systemResource;

        public AiShoppingGuideApp(
                        DashScopeChatModel dashScopeChatModel,
                        ChatMemory chatMemory,
                        @Value("classpath:/prompts/assistant-guide.st") Resource systemResource) {
                this.systemResource = systemResource;
                this.chatClient = ChatClient.builder(dashScopeChatModel)
                                .defaultAdvisors(
                                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                                new AppLoggerAdvisor())
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
                log.info(">>>> [AI Request] content: {}", content);
                return content;
        }

        public record ShoppingReport(String title, List<String> suggesions) {

        }

        public ShoppingReport doChatWithReport(String message, String chatId) {
                String dynamicSystem = new SystemPromptTemplate(systemResource)
                                .createMessage(Map.of("current_date", LocalDate.now().toString()))
                                .getText();
                dynamicSystem = dynamicSystem + "每次对话都要生成购物建议报告, 标题为{用户名}的购物建议报告, 内容为建议列表";
                ShoppingReport shoppingReport = chatClient
                                .prompt()
                                .system(dynamicSystem)
                                .user(message)
                                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                                .call()
                                .entity(ShoppingReport.class);
                log.info("<<<< [AI Response] ShoppingReport: {}", shoppingReport);
                return shoppingReport;
        }
}
