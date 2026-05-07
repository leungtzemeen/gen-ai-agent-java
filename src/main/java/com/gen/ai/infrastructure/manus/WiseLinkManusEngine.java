package com.gen.ai.infrastructure.manus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink Manus 2.0：Planning → ReAct；Observation 写入 {@link ChatMemory}；工具失败熔断与参数洗白。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WiseLinkManusEngine {

    /** 本地 3B 显存与脆弱的 MCP 工具：临时收紧步数上限 */
    private static final int MAX_STEPS = 10;

    /** 「只输出 Plan 不输出 Action」时最多自动追问次数，防止无限循环 */
    private static final int MAX_EMPTY_ACTION_NUDGES = 5;

    private static final String EXEC_SEMANTIC_GUIDANCE =
            "检测到执行异常，请反思是否参数构造有误，或尝试切换到备选执行路径。";

    private static final String CIRCUIT_BREAKER_ADVICE =
            "该工具当前物理故障或参数不兼容，请放弃此步骤，尝试调整 Plan，改用其他工具（如 searchProductOnWeb）获取信息或直接给出最终建议。";

    private final ChatMemory chatMemory;

    public ChatResponse runShoppingGuideReact(
            ChatClient chatClient,
            String systemMessage,
            String userMessage,
            List<ToolCallback> toolCallbacks,
            String conversationId,
            boolean useCategoryFilter,
            String category,
            Map<String, Object> toolContext) {
        Objects.requireNonNull(chatClient, "chatClient");
        Objects.requireNonNull(chatMemory, "chatMemory");
        String cid = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        Map<String, Object> ctxMap = toolContext == null ? Map.of() : Map.copyOf(toolContext);
        Map<String, ToolCallback> toolsByName = indexTools(toolCallbacks);
        ManusToolFailureState failureState = new ManusToolFailureState();
        int consecutiveEmptyActionNudges = 0;

        String planningSystem = Objects.requireNonNullElse(systemMessage, "") + ManusPromptSupport.PLANNING_APPEND;
        ChatResponse planResp =
                chatClient
                        .prompt()
                        .system(planningSystem)
                        .user(Objects.requireNonNullElse(userMessage, ""))
                        .toolCallbacks(List.of())
                        .toolContext(ctxMap)
                        .advisors(
                                spec -> applyShoppingMemoryAndRag(spec, cid, useCategoryFilter, category))
                        .call()
                        .chatResponse();

        String planAssistText = textFrom(planResp);
        AtomicReference<String> workingPlan =
                new AtomicReference<>(
                        ManusPromptSupport.extractPlan(planAssistText)
                                .orElse("(No structured ### Plan — proceed step by step from the user request.)"));
        log.debug("Manus planning extracted plan chars={}", workingPlan.get().length());

        String catalog = ManusPromptSupport.buildToolCatalog(toolCallbacks);
        for (int step = 1; step <= MAX_STEPS; step++) {
            String reactSystem =
                    Objects.requireNonNullElse(systemMessage, "")
                            + ManusPromptSupport.REACT_APPEND
                            + System.lineSeparator()
                            + System.lineSeparator()
                            + "### Tool catalog"
                            + System.lineSeparator()
                            + catalog;

            ChatResponse stepResp =
                    chatClient
                            .prompt()
                            .system(reactSystem)
                            .user(ManusPromptSupport.reactStepUserPrompt(workingPlan.get(), step, MAX_STEPS))
                            .toolCallbacks(List.of())
                            .toolContext(ctxMap)
                            .advisors(
                                    spec -> applyShoppingMemoryAndRag(spec, cid, useCategoryFilter, category))
                            .call()
                            .chatResponse();

            String assistantText = textFrom(stepResp);
            ManusPromptSupport.extractUpdatedPlan(assistantText).ifPresent(workingPlan::set);

            Optional<ManusParsedToolCall> parsed = ManusToolCallParser.tryParseToolCall(assistantText);
            if (parsed.isPresent()) {
                consecutiveEmptyActionNudges = 0;
                Optional<ManusParsedToolCall> sanitized = ManusToolCallParser.sanitizeParsedToolCall(parsed.get());
                if (sanitized.isEmpty()) {
                    String invalidSig = failureSignature(parsed.get().toolName(), "(invalid-map)");
                    boolean fusedInvalid =
                            trackFailure(
                                    failureState,
                                    invalidSig,
                                    true,
                                    parsed.get().toolName(),
                                    parsed.get().argumentsJson(),
                                    step);
                    String invalidMsg =
                            "tool_input 必须是 JSON 对象（Map），当前参数无法解析为对象。请修正 ### Action: 下的 JSON。";
                    if (fusedInvalid) {
                        invalidMsg =
                                CIRCUIT_BREAKER_ADVICE
                                        + System.lineSeparator()
                                        + System.lineSeparator()
                                        + invalidMsg;
                        log.warn(
                                "Manus circuit breaker OPEN (invalid-map) tool={} rawArgsSnippet={} step={}",
                                parsed.get().toolName(),
                                truncateForLog(parsed.get().argumentsJson()),
                                step);
                    } else {
                        log.warn(
                                "Manus args validation failed tool={} rawArgsSnippet={} step={}",
                                parsed.get().toolName(),
                                truncateForLog(parsed.get().argumentsJson()),
                                step);
                    }
                    pushObservation(cid, parsed.get().toolName(), invalidMsg);
                    continue;
                }
                handleToolCall(sanitized.get(), cid, toolsByName, ctxMap, step, failureState);
                continue;
            }

            Optional<String> fin = ManusToolCallParser.tryParseFinalAnswer(assistantText);
            if (fin.isPresent()) {
                log.info("Manus ReAct finished with FINAL_ANSWER at step {}", step);
                return wrap(fin.get());
            }

            if (ManusPromptSupport.assistantLooksLikePlanWithoutAction(assistantText)) {
                if (consecutiveEmptyActionNudges >= MAX_EMPTY_ACTION_NUDGES) {
                    log.warn(
                            "Manus empty-action nudge limit reached (max={}) step={} — returning assistant text",
                            MAX_EMPTY_ACTION_NUDGES,
                            step);
                    return wrap(assistantText == null ? "" : assistantText.strip());
                }
                chatMemory.add(cid, new UserMessage(ManusPromptSupport.emptyActionNudgeUserMessage()));
                consecutiveEmptyActionNudges++;
                log.info(
                        "Manus empty-action intercept step={} nudgeIndex={} — injected user reminder to emit ### Action:",
                        step,
                        consecutiveEmptyActionNudges);
                continue;
            }

            consecutiveEmptyActionNudges = 0;
            log.info("Manus ReAct ending step {} — no parseable tool JSON — returning assistant text", step);
            return wrap(assistantText == null ? "" : assistantText.strip());
        }

        return wrap("Terminated: reached Manus max steps (" + MAX_STEPS + ") without FINAL_ANSWER.");
    }

    private void pushObservation(String conversationId, String toolName, String body) {
        chatMemory.add(
                conversationId,
                new UserMessage(ManusPromptSupport.observationEnvelope(toolName, body)));
    }

    private void handleToolCall(
            ManusParsedToolCall call,
            String conversationId,
            Map<String, ToolCallback> toolsByName,
            Map<String, Object> ctxMap,
            int step,
            ManusToolFailureState failureState) {
        ToolInvokeResult outcome = invokeToolDetailed(call, toolsByName, ctxMap);
        String observation = outcome.observation();
        boolean failed = outcome.failure();

        if (failed) {
            observation = applyFailureSemantics(observation, outcome.thrown());
        }

        String sig = failureSignature(call.toolName(), call.argumentsJson());
        boolean fused = trackFailure(failureState, sig, failed, call.toolName(), call.argumentsJson(), step);
        if (fused) {
            observation = CIRCUIT_BREAKER_ADVICE + System.lineSeparator() + System.lineSeparator() + observation;
            log.warn(
                    "Manus circuit breaker OPEN tool={} args={} step={}",
                    call.toolName(),
                    truncateForLog(call.argumentsJson()),
                    step);
        } else if (failed) {
            log.warn(
                    "Manus tool failure tool={} args={} step={} consecutiveSameActionFailures={} throwableType={}",
                    call.toolName(),
                    truncateForLog(call.argumentsJson()),
                    step,
                    failureState.consecutiveFailures,
                    outcome.thrown() == null ? "none" : outcome.thrown().getClass().getName());
        }

        pushObservation(conversationId, call.toolName(), observation);
        log.info(
                "Manus ReAct step {} executed tool={} failed={} observationChars={}",
                step,
                call.toolName(),
                failed,
                observation.length());
    }

    /**
     * @return {@code true} 若本轮已触发熔断文案（连续同一 Action 失败 2 次）。
     */
    private static boolean trackFailure(
            ManusToolFailureState state,
            String signature,
            boolean failed,
            String toolName,
            String argsJson,
            int step) {
        if (!failed) {
            state.lastFailureSignature = null;
            state.consecutiveFailures = 0;
            return false;
        }
        if (signature.equals(state.lastFailureSignature)) {
            state.consecutiveFailures++;
        } else {
            state.lastFailureSignature = signature;
            state.consecutiveFailures = 1;
        }
        if (state.consecutiveFailures >= 2) {
            state.lastFailureSignature = null;
            state.consecutiveFailures = 0;
            return true;
        }
        return false;
    }

    private static String failureSignature(String toolName, String argumentsJson) {
        return toolName + "|" + Objects.requireNonNullElse(argumentsJson, "");
    }

    private static String applyFailureSemantics(String observation, Throwable thrown) {
        String body = observation == null ? "" : observation.strip();
        boolean needsGuidance =
                thrown != null
                        || looksLikeRemoteToolError(body)
                        || isFrameworkToolExecutionChain(thrown)
                        || body.regionMatches(true, 0, "error", 0, 5)
                        || body.contains("Error executing tool")
                        || body.contains("Error: Unknown tool");
        if (!needsGuidance) {
            return body;
        }
        return EXEC_SEMANTIC_GUIDANCE + System.lineSeparator() + System.lineSeparator() + body;
    }

    private static boolean looksLikeRemoteToolError(String observation) {
        if (observation == null || observation.isBlank()) {
            return false;
        }
        String s = observation;
        return s.contains("\"error\"")
                || s.contains("'error'")
                || s.toLowerCase().contains("[error]")
                || s.toLowerCase().contains("execution failed")
                || s.toLowerCase().contains("mcp");
    }

    private static boolean isFrameworkToolExecutionChain(Throwable t) {
        while (t != null) {
            if ("ToolExecutionException".equals(t.getClass().getSimpleName())) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String truncateForLog(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 512 ? t.substring(0, 509) + "..." : t;
    }

    private record ToolInvokeResult(String observation, boolean failure, Throwable thrown) {}

    private static ToolInvokeResult invokeToolDetailed(
            ManusParsedToolCall call, Map<String, ToolCallback> toolsByName, Map<String, Object> ctxMap) {
        ToolCallback tc = toolsByName.get(call.toolName());
        if (tc == null) {
            return new ToolInvokeResult(
                    "Error: Unknown tool `" + call.toolName() + "`. Use a name from the catalog.", true, null);
        }
        try {
            ToolContext ctx = new ToolContext(ctxMap);
            String out = tc.call(call.argumentsJson(), ctx);
            boolean failure =
                    out != null
                            && (out.strip().regionMatches(true, 0, "error", 0, 5)
                                    || looksLikeRemoteToolError(out));
            return new ToolInvokeResult(out == null ? "" : out, failure, null);
        } catch (Exception ex) {
            log.debug("Manus tool invocation exception tool={}", call.toolName(), ex);
            return new ToolInvokeResult(
                    "Error executing tool `" + call.toolName() + "`: " + ex.getMessage(), true, ex);
        }
    }

    private static void applyShoppingMemoryAndRag(
            ChatClient.AdvisorSpec spec, String conversationId, boolean useCategoryFilter, String category) {
        spec.param(ChatMemory.CONVERSATION_ID, (Object) conversationId);
        if (useCategoryFilter && category != null && !category.isBlank()) {
            Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", category).build();
            spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, exp);
        }
    }

    private static Map<String, ToolCallback> indexTools(List<ToolCallback> callbacks) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        if (callbacks == null) {
            return map;
        }
        for (ToolCallback tc : callbacks) {
            if (tc != null && tc.getToolDefinition() != null) {
                map.put(tc.getToolDefinition().name(), tc);
            }
        }
        return map;
    }

    private static String textFrom(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return "";
        }
        return Objects.requireNonNullElse(resp.getResult().getOutput().getText(), "");
    }

    private static ChatResponse wrap(String text) {
        String t = text == null ? "" : text;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(t))));
    }

    private static final class ManusToolFailureState {
        String lastFailureSignature;
        int consecutiveFailures;
    }
}
