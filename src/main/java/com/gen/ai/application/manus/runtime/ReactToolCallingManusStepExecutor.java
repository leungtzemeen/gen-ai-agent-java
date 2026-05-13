package com.gen.ai.application.manus.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import com.gen.ai.application.manus.api.ManusRunContext;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.api.ManusStepOutcome;
import com.gen.ai.application.manus.api.ManusTerminationReason;
import com.gen.ai.application.manus.policy.RagParticipationPolicy;
import com.gen.ai.application.shopping.AssistantGuidePersonaLoader;
import com.gen.ai.infrastructure.mcp.McpClientConfig.ShoppingGuideMergedToolCallbacks;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manus 可选执行器：鱼皮式「先 think（关内层自动 tool）再 act（{@link ToolCallingManager}）」，
 * 外层 {@link com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator} 每步可见一轮子任务。
 * <p>
 * <ul>
 *   <li>仅通过 {@link ToolCallingChatOptions#internalToolExecutionEnabled(Boolean)} 作用于<strong>本请求</strong>，
 *       不影响普通导购 {@link com.gen.ai.application.shopping.AiShoppingGuideApp}。</li>
 *   <li>仍使用冻结 {@link ChatClientManusChatRuntime}、{@link RagParticipationPolicy} 双 client 与
 *       {@link MessageChatMemoryAdvisor} 同一 {@link ChatMemory#CONVERSATION_ID}。</li>
 *   <li>工具预算、toolContext 与 {@link SpringAiManusStepExecutor} 对齐。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public final class ReactToolCallingManusStepExecutor implements ManusStepExecutor {

    private static final String CONTINUATION_USER_PROMPT =
            "请在本对话既有上下文与工具结果基础上继续完成用户需求；不要重复已确认的事实，若任务已完成请直接给出最终答复。";

    /** 与 Spring AI 1.1.x {@link ToolCallingChatOptions} 对齐；仅本 Executor 的 prompt 使用。 */
    private static final ToolCallingChatOptions REACT_TOOL_OPTIONS =
            ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();

    private final AssistantGuidePromptBundle assistantGuidePromptBundle;
    private final ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks;
    private final RagParticipationPolicy ragParticipationPolicy;
    private final ChatMemory chatMemory;
    private final ToolCallingManager toolCallingManager;

    @Override
    public ManusStepOutcome execute(ManusRunContext context, int stepIndex) {
        if (!(context.chatRuntime() instanceof ChatClientManusChatRuntime runtime)) {
            throw new IllegalStateException(
                    "ManusChatRuntime 必须为 ChatClientManusChatRuntime，实际为: "
                            + context.chatRuntime().getClass().getName());
        }

        ManusRunRequest request = context.request();
        ChatClient client = runtime.selectForStep(stepIndex, ragParticipationPolicy);
        String conversationId =
                (request.chatId() == null || request.chatId().isBlank()) ? "default" : request.chatId();

        log.info(
                ">>>> [Manus-ReactExecutor] traceId={} activeBrain={} step={} ragPolicy={} clientHash={} toolBudgetHash={}",
                context.traceId(),
                context.chatRuntime().activeBrainTag().orElse("-"),
                stepIndex,
                ragParticipationPolicy.useRag(stepIndex),
                System.identityHashCode(client),
                System.identityHashCode(context.manusTaskToolBudget()));

        String system = AssistantGuidePersonaLoader.loadPlainSystemPersona(assistantGuidePromptBundle);
        String category = request.category();
        boolean useCategoryFilter = category != null && !category.isBlank();
        String userMessage =
                stepIndex == 1 ? Objects.requireNonNullElse(request.userMessage(), "") : CONTINUATION_USER_PROMPT;

        long t0 = System.nanoTime();

        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(system)
                        .user(userMessage)
                        .toolCallbacks(
                                shoppingGuideMergedToolCallbacks.allToolCallbacks(context.manusTaskToolBudget()))
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
                        })
                        .options(REACT_TOOL_OPTIONS);

        ChatClient.CallResponseSpec callResponse = spec.call();
        ChatResponse chatResponse = callResponse.chatResponse();
        String thinkText = extractAssistantText(chatResponse);
        Optional<String> toolHint = extractToolHint(chatResponse);
        long elapsedMs = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
        Optional<Long> latency = Optional.of(elapsedMs);

        if (thinkText != null && thinkText.contains("本用户请求内所有工具调用额度已用尽")) {
            log.warn(
                    ">>>> [Manus-ReactExecutor] traceId={} step={} 命中工具预算断路提示，结束 Manus",
                    context.traceId(),
                    stepIndex);
            return ManusStepOutcome.finish(
                    ManusTerminationReason.MODEL_DONE,
                    summarizeForUi(thinkText),
                    latency,
                    Optional.of(false),
                    toolHint,
                    Optional.of(shortLine(summarizeForUi(thinkText), toolHint)));
        }

        if (chatResponse == null || !chatResponse.hasToolCalls()) {
            log.info(
                    ">>>> [Manus-ReactExecutor] traceId={} step={} think 后无工具调用，结束外层 Manus",
                    context.traceId(),
                    stepIndex);
            String preview = summarizeForUi(thinkText);
            return ManusStepOutcome.finish(
                    ManusTerminationReason.MODEL_DONE,
                    preview,
                    latency,
                    Optional.of(false),
                    toolHint,
                    Optional.of(shortLine(preview, toolHint)));
        }

        if (stepIndex >= request.maxSteps()) {
            log.info(
                    ">>>> [Manus-ReactExecutor] traceId={} step={} 已达 maxSteps，结束（think 仍有工具未执行）",
                    context.traceId(),
                    stepIndex);
            String preview = summarizeForUi(thinkText);
            return ManusStepOutcome.finish(
                    ManusTerminationReason.MODEL_DONE,
                    preview,
                    latency,
                    Optional.of(true),
                    toolHint,
                    Optional.of(shortLine(preview, toolHint)));
        }

        // act：合并 Memory 与本轮 assistant，再交给 ToolCallingManager
        List<Message> promptMessages = promptMessagesForToolCalls(conversationId, chatResponse);
        Prompt toolPrompt = new Prompt(promptMessages, REACT_TOOL_OPTIONS);
        log.info(
                ">>>> [Manus-ReactExecutor] traceId={} step={} act 执行工具 toolHint={} promptMessages={}",
                context.traceId(),
                stepIndex,
                toolHint.orElse("-"),
                promptMessages.size());

        ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(toolPrompt, chatResponse);
        replaceConversationFromToolResult(conversationId, toolResult);

        long elapsedTotal = Math.max(elapsedMs, (System.nanoTime() - t0) / 1_000_000L);
        latency = Optional.of(elapsedTotal);

        String fullAfterAct = summarizeConversationTailForUi(toolResult);
        Optional<String> shortAfterAct = Optional.of(shortLineForAct(toolHint));

        log.info(
                ">>>> [Manus-ReactExecutor] traceId={} step={} act 完成，返回 continueRun 进入下一外层步",
                context.traceId(),
                stepIndex);

        return ManusStepOutcome.continueRun(
                fullAfterAct, latency, Optional.of(true), toolHint, shortAfterAct);
    }

    /**
     * 供 {@link ToolCallingManager#executeToolCalls} 使用的 Prompt 消息列表。
     * <p>
     * {@link com.gen.ai.application.shopping.ShoppingGuideChatClientFactory} 注册的
     * {@code MessageChatMemoryAdvisor} 在 {@code chatResponse()} 返回时会把本轮带 {@code tool_calls} 的
     * {@link AssistantMessage} 写入 {@link ChatMemory}。而 Spring AI 的
     * {@code DefaultToolCallingManager} 在
     * {@code buildConversationHistoryAfterToolExecution} 里会对 {@code prompt.getInstructions()} 做拷贝后再
     * <strong>追加</strong> 同一条 assistant + {@link ToolResponseMessage}。
     * 若此处 instructions 仍含 Memory 里那条 assistant，会得到「assistant(tool) → assistant(tool) → tool」，
     * 违反 OpenAI/DeepSeek 等「每条含 tool_calls 的 assistant 后必须紧跟对应 tool 消息」的规则，触发 HTTP 400。
     */
    private List<Message> promptMessagesForToolCalls(String conversationId, ChatResponse chatResponse) {
        List<Message> messages = new ArrayList<>(chatMemory.get(conversationId));
        AssistantMessage fromResponse =
                chatResponse.getResult() != null ? chatResponse.getResult().getOutput() : null;
        if (fromResponse != null && !messages.isEmpty()) {
            int lastIdx = messages.size() - 1;
            Message last = messages.get(lastIdx);
            if (last instanceof AssistantMessage lastAm && lastHasSameToolCalls(lastAm, fromResponse)) {
                messages.remove(lastIdx);
            }
        }
        return messages;
    }

    private static boolean lastHasSameToolCalls(AssistantMessage last, AssistantMessage fromResponse) {
        if (!last.hasToolCalls() || !fromResponse.hasToolCalls()) {
            return false;
        }
        if (last.getToolCalls() == null || fromResponse.getToolCalls() == null) {
            return false;
        }
        return last.getToolCalls().equals(fromResponse.getToolCalls());
    }

    private void replaceConversationFromToolResult(String conversationId, ToolExecutionResult toolResult) {
        List<Message> next = toolResult.conversationHistory();
        if (next == null || next.isEmpty()) {
            return;
        }
        chatMemory.clear(conversationId);
        chatMemory.add(conversationId, next);
    }

    private static String summarizeConversationTailForUi(ToolExecutionResult toolResult) {
        List<Message> history = toolResult.conversationHistory();
        if (history == null || history.isEmpty()) {
            return "<empty after tool>";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof AssistantMessage am) {
                String t = am.getText();
                if (t != null && !t.isBlank()) {
                    return t.strip();
                }
            }
        }
        Message last = history.get(history.size() - 1);
        if (last instanceof ToolResponseMessage tr) {
            return tr.getResponses().stream()
                    .map(r -> r.name() + ": " + truncate(r.responseData(), 400))
                    .collect(Collectors.joining("\n"));
        }
        return last.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String shortLine(String full, Optional<String> toolHint) {
        if (toolHint.isPresent()) {
            return "工具: " + toolHint.get();
        }
        return truncate(full.replace('\n', ' '), 160);
    }

    private static String shortLineForAct(Optional<String> toolHint) {
        return "已执行工具: " + toolHint.orElse("(未解析名称)");
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

    private static Optional<String> extractToolHint(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return Optional.empty();
        }
        AssistantMessage msg = chatResponse.getResult().getOutput();
        if (msg == null || !msg.hasToolCalls() || msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return Optional.empty();
        }
        String joined =
                msg.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::name)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .collect(Collectors.joining(","));
        return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
    }

    private static String summarizeForUi(String text) {
        if (text == null || text.isBlank()) {
            return "<empty assistant content>";
        }
        return text.strip();
    }
}
