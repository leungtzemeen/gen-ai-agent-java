package com.gen.ai.application.minus.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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
 * <strong>终止策略（MVP）</strong>：当前实现为「每步调用一次模型后，仅在最外层最后一步
 * {@code stepIndex == request.maxSteps()} 时返回 {@link MinusTerminationReason#MODEL_DONE}」；中间步一律
 * {@link MinusStepOutcome#continueRun}，由编排层控制总步数。后续可扩展为解析模型/工具显式结束信号。
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

        String text = spec.call().content();
        String preview = summarizeForUi(text);

        if (text != null && text.contains("本用户请求内所有工具调用额度已用尽")) {
            log.warn(">>>> [Minus-StepExecutor] step={} 命中工具预算断路提示，结束 Minus", stepIndex);
            return MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, preview);
        }

        if (stepIndex >= request.maxSteps()) {
            log.info(">>>> [Minus-StepExecutor] step={} 已达 request.maxSteps，结束 Minus", stepIndex);
            return MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, preview);
        }

        return MinusStepOutcome.continueRun(preview);
    }

    private static String summarizeForUi(String text) {
        if (text == null || text.isBlank()) {
            return "<empty assistant content>";
        }
        String t = text.strip();
        int max = 400;
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
