package com.gen.ai.application.minus.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import com.gen.ai.application.minus.api.MinusRunContext;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.api.MinusStepOutcome;
import com.gen.ai.application.minus.api.MinusTerminationReason;
import com.gen.ai.application.minus.policy.RagParticipationPolicy;
import com.gen.ai.application.shopping.AssistantGuidePersonaLoader;
import com.gen.ai.infrastructure.mcp.McpClientConfig.ShoppingGuideMergedToolCallbacks;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3：每外层步调用一次 {@link ChatClient}（Spring AI 可在本步内完成 tool 内层循环），装配与
 * {@link com.gen.ai.application.shopping.AiShoppingGuideApp#doChat} 对齐的 system / tools / memory / 分区过滤。
 * <p>
 * <strong>终止策略</strong>：每步一次 {@code call()}（含 Spring AI 内层工具循环）后：
 * <ul>
 *   <li>若 {@link ChatResponse#hasToolCalls()} 为 {@code false}，视为本步已给出最终自然语言、未再请求新工具，
 *       结束外层 Minus（避免「任务已完成」仍跑满 {@code maxSteps}）。</li>
 *   <li>若仍请求工具，则继续外层步，直至达到 {@link MinusRunRequest#maxSteps()}。</li>
 *   <li>命中工具预算断路文案或已达 {@code maxSteps} 时同样结束。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class SpringAiMinusStepExecutor implements MinusStepExecutor {

    private static final String CONTINUATION_USER_PROMPT =
            "请在本对话既有上下文与工具结果基础上继续完成用户需求；不要重复已确认的事实，若任务已完成请直接给出最终答复。";

    private final AssistantGuidePromptBundle assistantGuidePromptBundle;
    private final ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks;
    private final RagParticipationPolicy ragParticipationPolicy;

    @Override
    public MinusStepOutcome execute(MinusRunContext context, int stepIndex) {
        if (!(context.chatRuntime() instanceof ChatClientMinusChatRuntime runtime)) {
            throw new IllegalStateException(
                    "MinusChatRuntime 必须为 ChatClientMinusChatRuntime，实际为: "
                            + context.chatRuntime().getClass().getName());
        }

        MinusRunRequest request = context.request();
        ChatClient client = runtime.selectForStep(stepIndex, ragParticipationPolicy);
        log.info(
                ">>>> [Minus-StepExecutor] step={} ragPolicy={} selectedClientHash={} toolBudgetCounterHash={}",
                stepIndex,
                ragParticipationPolicy.useRag(stepIndex),
                System.identityHashCode(client),
                System.identityHashCode(context.minusTaskToolBudget()));

        String system = AssistantGuidePersonaLoader.loadPlainSystemPersona(assistantGuidePromptBundle);
        String conversationId = (request.chatId() == null || request.chatId().isBlank()) ? "default" : request.chatId();
        String category = request.category();
        boolean useCategoryFilter = category != null && !category.isBlank();

        String userMessage =
                stepIndex == 1 ? Objects.requireNonNullElse(request.userMessage(), "") : CONTINUATION_USER_PROMPT;

        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(system)
                        .user(userMessage)
                        .toolCallbacks(
                                shoppingGuideMergedToolCallbacks.allToolCallbacks(context.minusTaskToolBudget()))
                        .toolContext(
                                new HashMap<>(
                                        Map.of(
                                                WiseLinkToolSecurityInterceptor.TOOL_CONTEXT_SESSION_ID_KEY,
                                                conversationId)))
                        .advisors(advisorSpec -> {
                            advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId);
                            if (useCategoryFilter) {
                                Filter.Expression exp =
                                        new FilterExpressionBuilder().eq("biz_category", category).build();
                                advisorSpec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
                            }
                        });

        ChatClient.CallResponseSpec callResponse = spec.call();
        // 只走一次 CallResponseSpec：先取 chatResponse；勿在 content() 之后再调 chatResponse()，否则会触发
        // DefaultAroundAdvisorChain「No CallAdvisors available to execute」（链已耗尽）。
        ChatResponse chatResponse = callResponse.chatResponse();
        String text = extractAssistantText(chatResponse);
        String preview = summarizeForUi(text);

        if (text != null && text.contains("本用户请求内所有工具调用额度已用尽")) {
            log.warn(">>>> [Minus-StepExecutor] step={} 命中工具预算断路提示，结束 Minus", stepIndex);
            return MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, preview);
        }

        if (chatResponse != null && !chatResponse.hasToolCalls()) {
            log.info(
                    ">>>> [Minus-StepExecutor] step={} 本步最终回复未再请求工具调用（hasToolCalls=false），结束 Minus 外层循环",
                    stepIndex);
            return MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, preview);
        }

        if (stepIndex >= request.maxSteps()) {
            log.info(">>>> [Minus-StepExecutor] step={} 已达 request.maxSteps，结束 Minus", stepIndex);
            return MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, preview);
        }

        return MinusStepOutcome.continueRun(preview);
    }

    private static String extractAssistantText(ChatResponse chatResponse) {
        if (chatResponse == null
                || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String t = chatResponse.getResult().getOutput().getText();
        return t != null ? t : "";
    }

    /**
     * 写入 {@link com.gen.ai.application.minus.api.MinusStepEvent} / {@link com.gen.ai.application.minus.api.MinusRunResult}
     * 的可见文本：须为<strong>完整</strong>助手回复（勿截断），否则 SSE 与 {@code event: done} 里用户只看到片段。
     */
    private static String summarizeForUi(String text) {
        if (text == null || text.isBlank()) {
            return "<empty assistant content>";
        }
        return text.strip();
    }
}
