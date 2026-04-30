package com.gen.ai.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.gen.ai.advisor.AppLoggerAdvisor;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AiShoppingGuideApp {

        private final ChatClient chatClient;

        private final Resource systemResource;

        public AiShoppingGuideApp(
                        ChatClient.Builder chatClientBuilder,
                        ChatMemory chatMemory,
                        VectorStore vectorStore,
                        @Value("classpath:/prompts/assistant-guide.st") Resource systemResource) {
                this.systemResource = systemResource;
                // 使用 Spring Boot 自动装配的 Builder，确保 Functions 等能力可通过名称解析并生效
                this.chatClient = chatClientBuilder
                                .defaultAdvisors(
                                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                                QuestionAnswerAdvisor.builder(vectorStore).build(),
                                                new AppLoggerAdvisor())
                                .build();
        }

        public String doChat(String message, String chatId) {
                String dynamicSystem = new SystemPromptTemplate(systemResource)
                                .createMessage(Map.of("current_date", LocalDate.now().toString()))
                                .getText();
                dynamicSystem = dynamicSystem
                                + System.lineSeparator()
                                + "当你需要查询商品具体的实时价格或库存时，请务必使用工具进行查询，不要凭空猜测。"
                                + "如果知识库（RAG）里没有实时价格或库存信息，必须调用工具获取。";
                ChatResponse response = chatClient
                                .prompt()
                                .system(dynamicSystem)
                                .user(message)
                                .toolNames("getProductPriceFunction", "getProductStockFunction")
                                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                                .call()
                                .chatResponse();
                String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                                ? ""
                                : response.getResult().getOutput().getText();
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
