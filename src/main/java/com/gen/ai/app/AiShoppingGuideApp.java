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
/**
 * AI 导购应用入口（Spring 组件）。
 * <p>
 * 负责将用户问题与 RAG 检索到的参考资料拼接成“增强提示词”，并通过 {@link ChatClient} 调用大模型。
 * 同时注入会话记忆（ChatMemory）与应用日志 advisor，以支持多轮对话与链路观测。
 */
public class AiShoppingGuideApp {

        private final ChatClient chatClient;

        private final Resource systemResource;
        private final RagDataService ragDataService;

        /**
         * 构建导购应用实例，并基于自动装配的 {@link ChatClient.Builder} 初始化 {@link ChatClient}。
         * <p>
         * - 使用 {@link MessageChatMemoryAdvisor} 将 {@link ChatMemory} 挂到对话链路中（按 conversationId 区分会话）<br>
         * - 使用 {@link AppLoggerAdvisor} 打印请求/响应关键日志<br>
         * - 注入系统提示词模板资源（{@code classpath:/prompts/assistant-guide.st}）
         */
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

        /**
         * 发起一次对话并返回文本结果（默认不按业务分类过滤 RAG）。
         * <p>
         * 等价于 {@link #doChat(String, String, String)} 的 category=null 版本。
         *
         * @param message 用户问题
         * @param chatId  会话 ID（为空时使用 default）
         * @return 模型输出的文本内容（可能为空字符串）
         */
        public String doChat(String message, String chatId) {
                return doChat(message, chatId, null);
        }

        /**
         * 发起一次对话并返回文本结果，可选按业务分类过滤 RAG 检索范围。
         * <p>
         * 主要步骤：渲染系统提示词模板（带 current_date）→ 拼装 RAG 参考资料与用户问题 → 调用模型并允许使用商品工具函数。
         *
         * @param message  用户问题
         * @param chatId   会话 ID（为空时使用 default）
         * @param category 业务分类（用于 RAG metadata 过滤；为空则全库检索）
         * @return 模型输出的文本内容（可能为空字符串）
         */
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

        /**
         * 将“用户问题 + RAG 检索结果”拼装为对模型更友好的输入提示词。
         * <p>
         * 内容包含：检索范围说明、参考资料（多个切片）、以及最终的用户问题。
         *
         * @param message  用户问题
         * @param category 业务分类（不为空时会进行分区检索过滤）
         * @return 拼装后的提示词文本
         */
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

        /**
         * 将检索到的 Document 列表渲染为提示词中的“参考资料”部分。
         * <p>
         * 每个片段会附带基础定位信息（source/chunk_index），并用分隔线隔开，便于模型引用与归纳。
         *
         * @param docs 检索结果
         * @return 适合直接拼入 prompt 的文本
         */
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

        /**
         * 根据用户问题文本做简单规则判断，推断可能的业务分类（用于分区 RAG 检索）。
         * <p>
         * 返回 null 表示不进行分类过滤（走全量知识库检索）。
         */
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

        /**
         * 结构化导购报告：用于将模型输出映射为强类型对象，便于前端/接口直接消费。
         *
         * @param title      报告标题
         * @param suggesions 建议列表（字段名保持与现有提示词/映射一致）
         */
        public record ShoppingReport(String title, List<String> suggesions) {

        }

        /**
         * 发起一次对话并让模型以 {@link ShoppingReport} 的结构化格式返回。
         * <p>
         * 会根据用户问题自动推断业务分类做分区检索（未命中则回退到全库检索），并把结果拼入增强提示词。
         *
         * @param message 用户问题
         * @param chatId  会话 ID（为空时使用 default）
         * @return 结构化的导购建议报告
         */
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
