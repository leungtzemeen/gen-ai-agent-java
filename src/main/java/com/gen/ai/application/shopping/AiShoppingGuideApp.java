package com.gen.ai.application.shopping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
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
 * <p>
 * 导购 HTTP/SSE 入口统一通过 {@link #doChat} 使用 {@link ChatClient#prompt()} {@code .call()}，
 * 以保证与 DashScope 等后端之间的<strong>完整多轮 tool_calls 循环</strong>；避免 {@code stream().content()}
 * 在同一会话第二轮起漏调 MCP 工具（表现为无 {@code [WiseLink-Export]} 而模型仍编造路径）。
 */
@Component
@Slf4j
public class AiShoppingGuideApp {

    private static final int STREAM_BACKPRESSURE_BUFFER_SIZE = 1024;

    /** 用户文案含导出 PDF / 选购报告意图时，对本轮 {@link ChatResponse} 做工具调用审计日志。 */
    private static final Pattern EXPORT_AUDIT_USER_PATTERN =
            Pattern.compile(
                    "(导出|重新导出|再导出|PDF|pdf|选购报告|购物建议书|留档|存档|exportShoppingReport"
                            + "|生成\\s*报告|更新\\s*报告|输出\\s*报告|保存\\s*报告|打印\\s*报告|再来.*份|还要.*pdf)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * 检测到用户 PDF/选购报告诉求时，在本轮 system 末尾追加，提高模型发起 {@code exportShoppingReport} 的概率；
     * 不写入人设模板文件，避免影响无导出诉求的轮次。
     */
    private static final String PDF_EXPORT_ENFORCEMENT_SYSTEM_APPENDIX =
            """
            \n
            <<<WISELINK_ROUNDTRIP:PDF_EXPORT_ENFORCEMENT>>>
            【本轮最高优先级 · 覆盖其它表述习惯】用户当前这句话明确要求生成/导出 PDF 选购报告（或同等含义）。
            你必须在本轮对话中实际调用 MCP 工具「exportShoppingReport」至少一次，并等待其返回后，再向用户说明 PDF 结果。
            在工具成功返回之前：禁止写出任何「*.pdf」文件名、禁止写出 exports 目录或磁盘绝对路径、禁止写「PDF 已生成完毕」「报告已保存」等结语。
            工具调用与导购正文可以编排为多步，但「声称文件已落盘」只能发生在工具返回成功之后；路径与文件名必须逐字来自工具返回文本，禁止自拟时间戳文件名。
            若工具返回失败，如实告知失败原因并请用户重试，禁止编造路径安慰用户。
            <<<WISELINK_ROUNDTRIP_END>>>
            """;

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
        String requestTraceId = UUID.randomUUID().toString().substring(0, 8);
        boolean useCategoryFilter = category != null && !category.isBlank();
        String userText = Objects.requireNonNullElse(message, "");

        if (EXPORT_AUDIT_USER_PATTERN.matcher(userText).find()) {
            systemMessage = systemMessage + PDF_EXPORT_ENFORCEMENT_SYSTEM_APPENDIX;
            log.info(
                    ">>>> [WiseLink-PDF] 本轮用户话命中导出/PDF 意图，已在 system 追加 PDF_EXPORT_ENFORCEMENT。conversationId={} requestTraceId={}",
                    conversationId,
                    requestTraceId);
        }

        AtomicBoolean exportShoppingReportInvoked = new AtomicBoolean(false);
        List<ToolCallback> toolCallbacks =
                wrapExportShoppingReportInvocationTracker(
                        shoppingGuideMergedToolCallbacks.allToolCallbacks(conversationId, requestTraceId),
                        exportShoppingReportInvoked);

        ChatResponse response = chatClient
                .prompt()
                .system(systemMessage)
                .user(userText)
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
        auditPdfExportToolCalls(response, conversationId, requestTraceId, userText);
        String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        if (EXPORT_AUDIT_USER_PATTERN.matcher(userText).find() && !exportShoppingReportInvoked.get()) {
            log.warn(
                    ">>>> [WiseLink-Export] 用户诉求含 PDF/选购报告，但本轮 exportShoppingReport 未被调用（工具已合并时多为模型漏调）。conversationId={} requestTraceId={}",
                    conversationId,
                    requestTraceId);
            content = appendExportNotInvokedUserNotice(content);
        }
        return content;
    }

    /**
     * 将 {@link ToolCallback} 列表中的 {@code exportShoppingReport} 包一层，在 JVM 侧得到「是否真执行过该工具」的事实，
     * 不依赖 {@link ChatResponse} 是否仍保留中间轮 tool_calls。
     */
    private static List<ToolCallback> wrapExportShoppingReportInvocationTracker(
            List<ToolCallback> callbacks, AtomicBoolean exportShoppingReportInvoked) {
        List<ToolCallback> wrapped = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            String name = def != null ? def.name() : "";
            if (name != null && name.contains("exportShoppingReport")) {
                wrapped.add(new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return cb.getToolDefinition();
                    }

                    @Override
                    public ToolMetadata getToolMetadata() {
                        return cb.getToolMetadata();
                    }

                    @Override
                    public String call(String toolInput) {
                        exportShoppingReportInvoked.set(true);
                        return cb.call(toolInput);
                    }

                    @Override
                    public String call(String toolInput, ToolContext toolContext) {
                        exportShoppingReportInvoked.set(true);
                        return cb.call(toolInput, toolContext);
                    }
                });
            } else {
                wrapped.add(cb);
            }
        }
        return wrapped;
    }

    private static String appendExportNotInvokedUserNotice(String content) {
        String notice =
                "\n\n---\n**系统提示**：本轮未检测到 PDF 导出工具（exportShoppingReport）的实际执行。若上文出现 `shopping-report-*.pdf`、磁盘路径或「PDF 已生成」等表述，均不可信。请再次明确要求「导出 PDF 选购报告」或稍后重试。";
        if (content == null || content.isBlank()) {
            return notice.stripLeading();
        }
        return content + notice;
    }

    /**
     * SSE 入口：统一委托 {@link #doChat}（{@code ChatClient.call()}），确保每轮请求均经过完整工具编排循环，
     * 避免纯 {@code stream().content()} 在同一会话后续轮次漏触发 MCP tool_calls。
     */
    public Flux<String> doChatStream(String message, String chatId, String category) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }

        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        log.info(
                ">>>> [WiseLink-Stream] SSE 导购统一走同步 ChatClient.call（完整工具循环），conversationId={}",
                conversationId);
        return Flux.defer(() -> Flux.just(doChat(message, chatId, category)))
                .subscribeOn(Schedulers.boundedElastic())
                .onBackpressureBuffer(
                        STREAM_BACKPRESSURE_BUFFER_SIZE,
                        BufferOverflowStrategy.ERROR);
    }

    /**
     * 根据助手侧 {@link ChatResponse} 中各 {@link Generation} 的 toolCalls 记录，对照用户是否在要导出 PDF。
     * <p>
     * <b>注意</b>：Spring AI 在 {@code ChatClient.call()} 完成多轮「assistant tool_calls → 工具执行 → 再次 assistant」后，
     * {@link ChatResponse#getResults()} 往往<strong>只保留最终一轮</strong>助手正文，<strong>不保留</strong>中间轮带
     * {@code tool_calls} 的 {@link Generation}，因此此处常见 {@code assistantToolCallNames=[]}、{@code hasToolCalls=false}，
     * <strong>不等于</strong>未调用 MCP。是否真实执行 {@code exportShoppingReport} 须看 MCP 子进程自身打印的导出日志
     * （wiselink-mcp-ecosystem 中的 WiseLink-Export 标记）；<strong>本应用不会打印该标记</strong>，审计里若出现同名短语仅为提示用语。
     */
    private static void auditPdfExportToolCalls(
            ChatResponse response, String conversationId, String requestTraceId, String userMessage) {
        if (userMessage == null || !EXPORT_AUDIT_USER_PATTERN.matcher(userMessage).find()) {
            return;
        }
        Set<String> toolNames = new LinkedHashSet<>();
        if (response != null && response.getResults() != null) {
            for (Generation gen : response.getResults()) {
                if (gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                    for (ToolCall tc : gen.getOutput().getToolCalls()) {
                        toolNames.add(tc.name());
                    }
                }
            }
        }
        boolean mentionsExportShoppingReport =
                toolNames.stream().anyMatch(n -> n != null && n.contains("exportShoppingReport"));
        log.info(
                ">>>> [WiseLink-ToolAudit] conversationId={} requestTraceId={} exportRelatedUser=true chatResponse.hasToolCalls={} assistantToolCallNamesInFinalResponse={} (空列表常见：最终响应常不含中间轮 tool_calls；以 MCP 日志为准)",
                conversationId,
                requestTraceId,
                response != null && response.hasToolCalls(),
                toolNames);
        if (!mentionsExportShoppingReport) {
            log.info(
                    ">>>> [WiseLink-ToolAudit] 最终 ChatResponse 未携带 exportShoppingReport 的 tool_calls 元数据。"
                            + " 说明：WiseLink-Export 仅由 MCP 子进程打印，本应用日志不会出现该标签。"
                            + " 若 MCP 工程在同时刻也无任何导出相关日志，则本轮很可能未触发 MCP 导出（再对照同 requestTraceId 的 [WiseLink-MCP-Tools] 行确认工具是否合并成功）。"
                            + " conversationId={} requestTraceId={}",
                    conversationId,
                    requestTraceId);
        }
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
