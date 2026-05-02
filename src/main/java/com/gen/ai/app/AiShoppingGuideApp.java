package com.gen.ai.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import com.gen.ai.advisor.AppLoggerAdvisor;
import com.gen.ai.exception.SensitivePromptException;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.service.SensitiveWordService;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
/**
 * AI 导购应用入口（Spring 组件）。
 * <p>
 * 通过 {@link RetrievalAugmentationAdvisor} 将 WiseLink RAG（查询压缩改写 → 分身检索 → 上下文注入）挂入 {@link ChatClient}；
 * 人设系统提示来自 {@link AssistantGuidePromptBundle}（解析自 {@code assistant-guide.st}）。
 */
public class AiShoppingGuideApp {

    private static final String ASSISTANT_GUIDE_VAR_CURRENT_DATE = "current_date";

    private final ChatClient chatClient;

    private final AssistantGuidePromptBundle assistantGuidePromptBundle;
    private final SensitiveWordService sensitiveWordService;

    public AiShoppingGuideApp(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor,
            AssistantGuidePromptBundle assistantGuidePromptBundle,
            SensitiveWordService sensitiveWordService) {
        this.assistantGuidePromptBundle = assistantGuidePromptBundle;
        this.sensitiveWordService = sensitiveWordService;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(Objects.requireNonNull(chatMemory)).build(),
                        wiseLinkRetrievalAugmentationAdvisor,
                        new AppLoggerAdvisor())
                .build();
    }

    @PostConstruct
    void logWiseLinkPersonaLoaded() {
        log.info(">>>> [System] WiseLink 金牌导购人设加载成功（含 Modular RAG Advisor）。");
    }

    public String doChat(String message, String chatId) {
        return doChat(message, chatId, null);
    }

    /**
     * 发起对话：原始用户句直接进入链路，由 {@link RetrievalAugmentationAdvisor} 完成检索增强；
     * 可选传入 {@code category} 通过 {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} 做分区过滤。
     */
    public String doChat(String message, String chatId, String category) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            throw new SensitivePromptException();
        }

        String systemMessage = renderAssistantGuideSystemPrompt();
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        boolean useCategoryFilter = category != null && !category.isBlank();

        ChatResponse response = chatClient
                .prompt()
                .system(systemMessage)
                .user(Objects.requireNonNullElse(message, ""))
                .toolNames("getProductPriceFunction", "getProductStockFunction")
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId);
                    if (useCategoryFilter) {
                        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", category).build();
                        spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                    }
                })
                .call()
                .chatResponse();
        String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        log.info(">>>> [AI Request] content: {}", content);
        return content;
    }

    private String renderAssistantGuideSystemPrompt() {
        String today = LocalDate.now().toString();
        String rendered = new SystemPromptTemplate(assistantGuidePromptBundle.systemPromptResource())
                .createMessage(Map.of(ASSISTANT_GUIDE_VAR_CURRENT_DATE, today))
                .getText();
        if (rendered != null && rendered.contains("{current_date}")) {
            log.warn(
                    ">>>> [System] assistant-guide.st 中的日期占位符未被替换，请确认模板变量名为：{}",
                    ASSISTANT_GUIDE_VAR_CURRENT_DATE);
        }
        return Objects.requireNonNullElse(rendered, "");
    }

    /**
     * 根据用户问题文本做简单规则判断，推断可能的业务分类（用于分区 RAG 检索）。
     */
    private static String inferBizCategoryFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String p = prompt.toLowerCase();

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
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            throw new SensitivePromptException();
        }

        String dynamicSystem = renderAssistantGuideSystemPrompt()
                + "每次对话都要生成购物建议报告, 标题为{用户名}的购物建议报告, 内容为建议列表";

        String bizCategory = inferBizCategoryFromPrompt(message);
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;

        ShoppingReport shoppingReport = chatClient
                .prompt()
                .system(dynamicSystem)
                .user(Objects.requireNonNullElse(message, ""))
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId);
                    if (bizCategory != null) {
                        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", bizCategory).build();
                        spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                    }
                })
                .call()
                .entity(ShoppingReport.class);
        log.info("<<<< [AI Response] ShoppingReport: {}", shoppingReport);
        return shoppingReport;
    }
}
