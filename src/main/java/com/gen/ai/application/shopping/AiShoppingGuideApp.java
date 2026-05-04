package com.gen.ai.application.shopping;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
import com.gen.ai.common.exception.SensitivePromptException;
import com.gen.ai.infrastructure.mcp.McpClientConfig.ShoppingGuideMergedToolCallbacks;
import com.gen.ai.infrastructure.security.SensitiveWordService;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AI 导购应用入口（Spring 组件）。
 * <p>
 * 通过 {@link RetrievalAugmentationAdvisor} 将 WiseLink RAG（查询压缩改写 → 分身检索 → 上下文注入）挂入 {@link ChatClient}；
 * 人设系统提示来自 {@link AssistantGuidePromptBundle}（解析自 {@code assistant-guide.st}）。
 */
@Component
@Slf4j
public class AiShoppingGuideApp {

    private static final int STREAM_BACKPRESSURE_BUFFER_SIZE = 1024;

    /** 启发式：更像会触发 WiseLink 工具（PDF/价格/库存/下载等）的提问走阻塞 call，先完整执行工具再一次性返回，避免与流式冲突。 */
    private static final Pattern LIKELY_TOOL_QUERY =
            Pattern.compile(
                    "(导出|PDF|pdf|报告|购物建议书|说明书|下载|库存|价格|多少钱|全网|比价|网页|抓取|记住|意向)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String ASSISTANT_GUIDE_VAR_CURRENT_DATE = "current_date";

    private final ChatClient chatClient;

    private final AssistantGuidePromptBundle assistantGuidePromptBundle;
    private final SensitiveWordService sensitiveWordService;
    private final ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks;

    public AiShoppingGuideApp(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor,
            AssistantGuidePromptBundle assistantGuidePromptBundle,
            SensitiveWordService sensitiveWordService,
            ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks) {
        this.assistantGuidePromptBundle = assistantGuidePromptBundle;
        this.sensitiveWordService = sensitiveWordService;
        this.shoppingGuideMergedToolCallbacks = shoppingGuideMergedToolCallbacks;
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

    /**
     * 发起对话，不按品类过滤向量检索（等价于 {@link #doChat(String, String, String)} 且 {@code category} 为 {@code null}）。
     */
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
                .toolCallbacks(shoppingGuideMergedToolCallbacks.allToolCallbacks())
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
        String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        return content;
    }

    /**
     * 流式对话：默认对「更像会触发工具」的请求先走完整 {@link #doChat}（工具执行完毕后再下发），
     * 其余请求使用 {@link ChatClient} {@code stream().content()} 按 token/片段推送。
     */
    public Flux<String> doChatStream(String message, String chatId, String category) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }

        if (likelyNeedsToolFirst(message)) {
            log.info(">>>> [WiseLink-Stream] 启发式判定可能涉及工具调用，先同步执行完整链路再下发结果");
            return Flux.defer(() -> Flux.just(doChat(message, chatId, category)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onBackpressureBuffer(
                            STREAM_BACKPRESSURE_BUFFER_SIZE,
                            BufferOverflowStrategy.ERROR);
        }

        String systemMessage = renderAssistantGuideSystemPrompt();
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        boolean useCategoryFilter = category != null && !category.isBlank();

        Flux<String> tokens = chatClient
                .prompt()
                .system(systemMessage)
                .user(Objects.requireNonNullElse(message, ""))
                .toolCallbacks(shoppingGuideMergedToolCallbacks.allToolCallbacks())
                .toolContext(Map.of(WiseLinkToolSecurityInterceptor.TOOL_CONTEXT_SESSION_ID_KEY, conversationId))
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId);
                    if (useCategoryFilter) {
                        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", category).build();
                        spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                    }
                })
                .stream()
                .content();

        return tokens
                .onBackpressureBuffer(
                        STREAM_BACKPRESSURE_BUFFER_SIZE,
                        BufferOverflowStrategy.ERROR);
    }

    private static boolean likelyNeedsToolFirst(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return LIKELY_TOOL_QUERY.matcher(message).find();
    }

    /** 渲染人设系统提示（替换 {@code assistant-guide.st} 中的日期等变量）。 */
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
        return shoppingReport;
    }
}
