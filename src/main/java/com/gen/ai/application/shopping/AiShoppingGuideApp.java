package com.gen.ai.application.shopping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;

import com.gen.ai.common.exception.SensitivePromptException;
import com.gen.ai.infrastructure.mcp.McpClientConfig.ShoppingGuideMergedToolCallbacks;
import com.gen.ai.infrastructure.model.WiseLinkChatClientFactory;
import com.gen.ai.infrastructure.model.WiseLinkLlmProfile;
import com.gen.ai.infrastructure.security.SensitiveWordService;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AI 导购应用入口（Spring 组件）。WiseLink 4.0：人设仅来自 {@code assistant-guide.st}，
 * 工具列表为每请求动态合流结果；由模型与工具 Schema 原生驱动决策。
 * <p>
 * 通过 RAG Advisor 将 WiseLink RAG 挂入 {@link ChatClient}；
 * 导购入口统一通过 {@link #doChat} 使用 {@link ChatClient#prompt()} {@code .call()}，
 * 以保证与后端之间的完整多轮 tool_calls 循环。
 */
@Component
@Slf4j
public class AiShoppingGuideApp {

    private static final int STREAM_BACKPRESSURE_BUFFER_SIZE = 1024;

    private final WiseLinkChatClientFactory chatClientFactory;

    private final AssistantGuidePromptBundle assistantGuidePromptBundle;
    private final SensitiveWordService sensitiveWordService;
    private final ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks;

    public AiShoppingGuideApp(
            WiseLinkChatClientFactory chatClientFactory,
            AssistantGuidePromptBundle assistantGuidePromptBundle,
            SensitiveWordService sensitiveWordService,
            ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks) {
        this.chatClientFactory = Objects.requireNonNull(chatClientFactory);
        this.assistantGuidePromptBundle = assistantGuidePromptBundle;
        this.sensitiveWordService = sensitiveWordService;
        this.shoppingGuideMergedToolCallbacks = shoppingGuideMergedToolCallbacks;
    }

    @PostConstruct
    void logWiseLinkPersonaLoaded() {
        log.info(">>>> [System] WiseLink 金牌导购人设加载成功（含 Modular RAG Advisor）。");
    }

    /**
     * 发起对话，不按品类过滤向量检索（等价于 {@link #doChat(String, String, String)} 且 {@code category} 为 {@code null}）。
     */
    public String doChat(String message, String chatId) {
        return doChat(message, chatId, null);
    }

    /**
     * 发起对话：原始用户句直接进入链路，由 RAG Advisor 完成检索增强；
     * 可选传入 {@code category} 通过 {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} 做分区过滤。
     */
    public String doChat(String message, String chatId, String category) {
        return doChat(message, chatId, category, WiseLinkLlmProfile.QWEN);
    }

    /**
     * @param llmProfile 选用的对话模型配置，默认 {@link WiseLinkLlmProfile#QWEN}。
     */
    public String doChat(String message, String chatId, String category, WiseLinkLlmProfile llmProfile) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            throw new SensitivePromptException();
        }

        String userText = Objects.requireNonNullElse(message, "");
        WiseLinkLlmProfile profile = llmProfile == null ? WiseLinkLlmProfile.QWEN : llmProfile;
        ChatClient chatClient = chatClientFactory.chatClient(profile);

        String systemMessage = renderAssistantGuideSystemPrompt();
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        String requestTraceId = UUID.randomUUID().toString().substring(0, 8);
        boolean useCategoryFilter = category != null && !category.isBlank();

        List<ToolCallback> toolCallbacks =
                shoppingGuideMergedToolCallbacks.allToolCallbacks(conversationId, requestTraceId);

        ChatResponse response =
                invokeShoppingGuideChat(
                        chatClient,
                        systemMessage,
                        userText,
                        toolCallbacks,
                        conversationId,
                        useCategoryFilter,
                        category);

        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : Objects.requireNonNullElse(response.getResult().getOutput().getText(), "");
    }

    private ChatResponse invokeShoppingGuideChat(
            ChatClient chatClient,
            String systemMessage,
            String userMessage,
            List<ToolCallback> toolCallbacks,
            String conversationId,
            boolean useCategoryFilter,
            String category) {
        return chatClient
                .prompt()
                .system(systemMessage)
                .user(userMessage)
                .toolCallbacks(toolCallbacks)
                .toolContext(Map.of(WiseLinkToolSecurityInterceptor.TOOL_CONTEXT_SESSION_ID_KEY, conversationId))
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId);
                    if (useCategoryFilter) {
                        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", category).build();
                        spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                    }
                })
                .call()
                .chatResponse();
    }

    /**
     * SSE 入口：委托 {@link #doChat}（{@code ChatClient.call()}），每轮完整工具编排循环。
     */
    public Flux<String> doChatStream(String message, String chatId, String category) {
        return doChatStream(message, chatId, category, WiseLinkLlmProfile.QWEN);
    }

    public Flux<String> doChatStream(String message, String chatId, String category, WiseLinkLlmProfile llmProfile) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }

        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        WiseLinkLlmProfile profile = llmProfile == null ? WiseLinkLlmProfile.QWEN : llmProfile;
        log.info(
                ">>>> [WiseLink-Stream] SSE 导购走同步 ChatClient.call（完整工具循环），conversationId={} llmProfile={}",
                conversationId,
                profile);
        return Flux.defer(() -> Flux.just(doChat(message, chatId, category, profile)))
                .subscribeOn(Schedulers.boundedElastic())
                .onBackpressureBuffer(
                        STREAM_BACKPRESSURE_BUFFER_SIZE,
                        BufferOverflowStrategy.ERROR);
    }

    /** 读取 {@link AssistantGuidePromptBundle#systemPromptResource()} 中的人设正文（已剥离 RAG 分段后的纯文本）。 */
    private String renderAssistantGuideSystemPrompt() {
        Resource resource = assistantGuidePromptBundle.systemPromptResource();
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read assistant guide system prompt resource", ex);
        }
    }

    /**
     * 根据用户问题文本做简单规则判断，推断可能的业务分类（用于分区 RAG 检索）。
     */
    private static String inferBizCategoryFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String p = prompt.toLowerCase(Locale.ROOT);

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

    /** 结构化购物建议报告（由模型按当前对话生成的标题与建议列表）。 */
    public record ShoppingReport(String title, List<String> suggesions) {

    }

    /**
     * 带「购物建议报告」输出的对话：在系统提示中追加报告格式约束，并按关键词推断 {@code biz_category} 做分区检索。
     */
    public ShoppingReport doChatWithReport(String message, String chatId) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            throw new SensitivePromptException();
        }

        String dynamicSystem = renderAssistantGuideSystemPrompt()
                + "每次对话都要生成购物建议报告, 标题为{用户名}的购物建议报告, 内容为建议列表";

        String bizCategory = inferBizCategoryFromPrompt(message);
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;

        ShoppingReport shoppingReport = chatClientFactory
                .chatClient(WiseLinkLlmProfile.QWEN)
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
        return shoppingReport;
    }
}
