package com.gen.ai.application.shopping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.gen.ai.advisor.AppLoggerAdvisor;
import com.gen.ai.common.exception.SensitivePromptException;
import com.gen.ai.infrastructure.mcp.McpClientConfig.ShoppingGuideMergedToolCallbacks;
import com.gen.ai.infrastructure.security.SensitiveWordService;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 导购应用入口（Spring 组件）。
 * <p>
 * 通过 {@link RetrievalAugmentationAdvisor} 将 WiseLink RAG（查询压缩改写 → 分身检索 →
 * 上下文注入）挂入 {@link ChatClient}；
 * 人设系统提示来自 {@link AssistantGuidePromptBundle}（解析自 {@code assistant-guide.st}）。
 */
@Component
@Slf4j
public class AiShoppingGuideApp {

    private static final int STREAM_BACKPRESSURE_BUFFER_SIZE = 1024;

    /** 启发式：更像会触发 WiseLink 工具（PDF/价格/库存/下载等）的提问走阻塞 call，先完整执行工具再一次性返回，避免与流式冲突。 */
    private static final Pattern LIKELY_TOOL_QUERY = Pattern.compile(
            "(导出|PDF|pdf|报告|购物建议书|说明书|下载|库存|价格|多少钱|全网|比价|网页|抓取|记住|意向)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        log.info(">>>> [System-Init] WiseLinkAI [流/阻双通道自愈分流网关] 全线安全点火就绪。");
    }

    /**
     * 发起对话：原始用户句直接进入链路，由 {@link RetrievalAugmentationAdvisor} 完成检索增强；
     * 可选传入 {@code category} 通过
     * {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} 做分区过滤。
     */
    public String doChat(String message, String chatId, String category) {
        if (sensitiveWordService.containsSensitiveWord(message)) {
            log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
            throw new SensitivePromptException();
        }

        var spec = buildUnifiedChatSpec(message, chatId, category);
        return spec.call().content();
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
        // 如果是 null，它会自动温和降级自愈为一个纯净的空字符串 ""
        // 如果是脏字符，Java 11 的 .strip()
        // 会在内存级别以最高性能抹去两端所有的空白、回车噪音，保证递给百炼大模型的是绝对纯净、没有任何格式污染的“核心提问文本”
        String cleanedMsg = message == null ? "" : message.strip();
        // 先人肉通读，如果是调工具，强行改走阻塞通道
        if (likelyNeedsToolFirst(cleanedMsg)) {
            log.info(">>>> [WiseLink-Stream] 启发式判定可能涉及工具调用，先同步执行完整链路再下发结果");
            return Mono.fromCallable(() -> doChat(cleanedMsg, chatId, category))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flux();
        }
        // 纯聊天场景：平滑放行，走原生的纯流式逐字吐泡泡通道
        var spec = buildUnifiedChatSpec(cleanedMsg, chatId, category);
        return spec.stream().content()
                .onBackpressureBuffer(
                        STREAM_BACKPRESSURE_BUFFER_SIZE, // 1024 额度锁死
                        BufferOverflowStrategy.ERROR);
    }

    /**
     * 架构师统一组装中台：把 20 多行一模一样的 ChatClient 链条永久收口！
     * 一处修改，双通道（Stream/Call）瞬间全局同步
     */
    private ChatClient.ChatClientRequestSpec buildUnifiedChatSpec(String message, String chatId, String category) {
        String systemMessage = renderAssistantGuideSystemPrompt();
        String conversationId = (chatId == null || chatId.isBlank()) ? "default" : chatId;
        boolean useCategoryFilter = category != null && !category.isBlank();

        AtomicInteger perRequestToolInvocations = new AtomicInteger(0);

        return chatClient.prompt()
                .system(systemMessage)
                .user(Objects.requireNonNullElse(message, ""))
                // 计数器透传
                .toolCallbacks(shoppingGuideMergedToolCallbacks.allToolCallbacks(perRequestToolInvocations))
                // 用可读写的 HashMap 替换只读 Map.of，彻底封死底层 NPE
                .toolContext(new HashMap<>(Map.of(
                        WiseLinkToolSecurityInterceptor.TOOL_CONTEXT_SESSION_ID_KEY, conversationId)))
                .advisors(spec -> {
                    spec.param(ChatMemory.CONVERSATION_ID, conversationId);
                    if (useCategoryFilter) {
                        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", category).build();
                        spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                    }
                });
    }

    private static boolean likelyNeedsToolFirst(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return LIKELY_TOOL_QUERY.matcher(message).find();
    }

    /**
     * WiseLink 3.0 提示词纯净化升级：
     * 直接从微内核注册中心提取彻底脱水、无任何动态变量摩擦的纯净静态核心人设。
     */
    private String renderAssistantGuideSystemPrompt() {
        try {
            // 完美合龙：直接通过我们写好的強类型星图，秒级获取最纯净的人设人设文本
            // 如果你的 SystemPrompt 也归口到了 Bundle 里，可以用它来优雅接货
            Resource systemResource = assistantGuidePromptBundle.getSystemResource();

            if (systemResource == null) {
                return "";
            }

            // 利用 Spring 的工具类，直接将只读 Resource 转化为纯净字符串，不留任何魔法断层
            String rendered = org.springframework.util.StreamUtils.copyToString(
                    systemResource.getInputStream(),
                    StandardCharsets.UTF_8);

            return Objects.requireNonNullElse(rendered.strip(), "");

        } catch (IOException e) {
            log.error(">>>> [System-Prompt-Error] 核心人设物理文件加载失败，启动空字符兜底！", e);
            return "";
        }
    }

}
