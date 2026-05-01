package com.gen.ai.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.gen.ai.advisor.AppLoggerAdvisor;
import com.gen.ai.service.RagDataService;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class AiShoppingGuideApp {

        private final ChatClient chatClient;

        private final Resource systemResource;
        private final RagDataService ragDataService;

        public AiShoppingGuideApp(
                        ChatClient.Builder chatClientBuilder,
                        ChatMemory chatMemory,
                        RagDataService ragDataService,
                        @Value("classpath:/prompts/assistant-guide.st") Resource systemResource) {
                this.systemResource = systemResource;
                this.ragDataService = ragDataService;
                // 使用 Spring Boot 自动装配的 Builder，确保 Functions 等能力可通过名称解析并生效
                this.chatClient = chatClientBuilder
                                .defaultAdvisors(
                                                MessageChatMemoryAdvisor.builder(Objects.requireNonNull(chatMemory)).build(),
                                                new AppLoggerAdvisor())
                                .build();
        }

        public String doChat(String message, String chatId) {
                return doChat(message, chatId, null);
        }

        public String doChat(String message, String chatId, String category) {
                String dynamicSystem = new SystemPromptTemplate(systemResource)
                                .createMessage(Map.of("current_date", LocalDate.now().toString()))
                                .getText();
                dynamicSystem = dynamicSystem
                                + System.lineSeparator()
                                + "当你需要查询商品具体的实时价格或库存时，请务必使用工具进行查询，不要凭空猜测。"
                                + "如果知识库（RAG）里没有实时价格或库存信息，必须调用工具获取。"
                                + System.lineSeparator()
                                + "你搜到的参考资料可能包含断断续续的编号或参数，请将其整理成自然、亲切的导购语言，不要直接复读原文。"
                                + "如果资料中没有直接回答用户的问题，请基于现有知识给出专业建议或引导。";

                String enrichedUserPrompt = buildEnrichedUserPrompt(message, category);
                String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
                ChatResponse response = chatClient
                                .prompt()
                                .system(dynamicSystem)
                                .user(Objects.requireNonNull(enrichedUserPrompt))
                                .toolNames("getProductPriceFunction", "getProductStockFunction")
                                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId))
                                .call()
                                .chatResponse();
                String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                                ? ""
                                : response.getResult().getOutput().getText();
                log.info(">>>> [AI Request] content: {}", content);
                return content;
        }

        private String buildEnrichedUserPrompt(String message, String category) {
                String prompt = Objects.requireNonNullElse(message, "");

                List<Document> docs;
                boolean useCategoryFilter = category != null && !category.isBlank();
                docs = useCategoryFilter
                                ? ragDataService.similaritySearch(prompt, category)
                                : ragDataService.similaritySearch(prompt);

                StringBuilder sb = new StringBuilder();
                sb.append("【检索范围】");
                if (!useCategoryFilter) {
                        sb.append("全量知识库（不分区）");
                } else {
                        sb.append("biz_category=").append(category);
                }
                sb.append(System.lineSeparator());

                sb.append("【参考资料】").append(System.lineSeparator());
                sb.append(renderDocsForPrompt(docs));
                sb.append(System.lineSeparator());
                sb.append("【用户问题】").append(prompt);
                return sb.toString();
        }

        private static String renderDocsForPrompt(List<Document> docs) {
                if (docs == null || docs.isEmpty()) {
                        return "（未检索到直接相关资料）";
                }
                List<String> chunks = new ArrayList<>(docs.size());
                for (int i = 0; i < docs.size(); i++) {
                        Document d = docs.get(i);
                        if (d == null) {
                                continue;
                        }
                        Object source = d.getMetadata() == null ? null : d.getMetadata().get("source");
                        Object chunkIndex = d.getMetadata() == null ? null : d.getMetadata().get("chunk_index");
                        String header = "片段#" + i
                                        + (source == null ? "" : (" source=" + source))
                                        + (chunkIndex == null ? "" : (" chunk_index=" + chunkIndex));
                        chunks.add(header + System.lineSeparator() + d.getText());
                }
                return String.join(System.lineSeparator() + "-----" + System.lineSeparator(), chunks);
        }

        private static String inferBizCategoryFromPrompt(String prompt) {
                if (prompt == null || prompt.isBlank()) {
                        return null;
                }
                String p = prompt.toLowerCase();

                // 家电清洗优先：很多问题会同时提到“空调/油烟机”等设备名
                if (p.contains("cleaning") || p.contains("清洗") || p.contains("清洁") || p.contains("除菌") || p.contains("消毒")) {
                        return "家电清洗";
                }
                if (p.contains("health") || p.contains("运动") || p.contains("健康") || p.contains("健身") || p.contains("跑步")
                                || p.contains("心率") || p.contains("瑜伽") || p.contains("体脂")) {
                        return "运动健康";
                }
                if (p.contains("audio-visual") || p.contains("影音") || p.contains("电视") || p.contains("投影") || p.contains("音响")
                                || p.contains("耳机") || p.contains("家庭影院") || p.contains("4k") || p.contains("hdr") || p.contains("hdmi")) {
                        return "影音导购";
                }
                return null;
        }

        public record ShoppingReport(String title, List<String> suggesions) {

        }

        public ShoppingReport doChatWithReport(String message, String chatId) {
                String dynamicSystem = new SystemPromptTemplate(systemResource)
                                .createMessage(Map.of("current_date", LocalDate.now().toString()))
                                .getText();
                dynamicSystem = dynamicSystem + "每次对话都要生成购物建议报告, 标题为{用户名}的购物建议报告, 内容为建议列表";

                String bizCategory = inferBizCategoryFromPrompt(message);
                String enrichedUserPrompt = buildEnrichedUserPrompt(message, bizCategory);
                String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
                ShoppingReport shoppingReport = chatClient
                                .prompt()
                                .system(dynamicSystem)
                                .user(Objects.requireNonNull(enrichedUserPrompt))
                                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId))
                                .call()
                                .entity(ShoppingReport.class);
                log.info("<<<< [AI Response] ShoppingReport: {}", shoppingReport);
                return shoppingReport;
        }
}
