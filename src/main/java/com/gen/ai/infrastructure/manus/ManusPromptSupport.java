package com.gen.ai.infrastructure.manus;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Open-Manus-style planning + ReAct instructions and tool catalog text for small models.
 */
public final class ManusPromptSupport {

    private static final Pattern PLAN_SECTION =
            Pattern.compile("(?is)###\\s*Plan\\s*\\R+(.*?)(?=###|\\z)");

    private static final Pattern UPDATED_PLAN_SECTION =
            Pattern.compile("(?is)###\\s*Updated\\s*Plan\\s*\\R+(.*?)(?=###|\\z)");

    static final String PLANNING_APPEND =
            """

                    ### Planning phase (required first)
                    Before any tools, write a concise numbered plan under markdown heading ### Plan (3–7 steps).
                    Do not emit Action or tool JSON in this phase — planning text only.""";

    static final String REACT_APPEND =
            """

                    ### ReAct execution protocol
                    Maintain a strict chain: Thought → Action → (system provides Observation) → Thought → …
                    Each turn you output:
                    - Thought: reasoning aligned with the current Plan (revise the Plan when observations contradict it — emit ### Updated Plan when you change course).

                    **Plan–Action lock (Step 1 of this loop):** After you state ### Plan (or recap the plan) in your reply for **Step 1**, you MUST **in the same assistant message** immediately continue with a line ### Action: (or Action:) and the JSON for the **first** tool call from that plan. **Do not** answer with only ### Plan / Thought and stop — always pair the plan with the first executable ### Action in one shot (unless you instead output FINAL_ANSWER because no tools are needed).

                    When calling a tool, put the Action JSON on lines following ### Action: (or Action:). Prefer minimal prose between the label and the opening `{`; the JSON object itself must be one valid tool call.

                    JSON shape (required for tools):
                    {"action":"<tool_name>","action_input":{ ... }}

                    Alternate keys accepted: name/tool for the tool name; arguments/input/tool_input for parameters.

                    When you have enough information to answer the shopper clearly without tools, end with a line:
                    FINAL_ANSWER: <natural language reply>

                    Rules:
                    - Use only tool names listed in the catalog.
                    - Keep Thought short when calling tools; expand in FINAL_ANSWER.""";

    private static final String STEP1_USER_PLAN_ACTION_SYNC =
            """
                    【本步为 ReAct Step 1】若你输出 ### Plan（或对计划的复述），必须在同一条回复里紧接着给出 ### Action: 以及第一个工具的 JSON；禁止仅输出计划而不执行动作（除非直接 FINAL_ANSWER）。""";

    private ManusPromptSupport() {}

    public static String buildToolCatalog(List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return "(no tools)";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCallback tc : callbacks) {
            ToolDefinition d = tc.getToolDefinition();
            sb.append("- ")
                    .append(d.name())
                    .append(": ")
                    .append(trimDesc(d.description()))
                    .append(System.lineSeparator());
        }
        return sb.toString().strip();
    }

    private static String trimDesc(String d) {
        if (d == null) {
            return "";
        }
        String t = d.strip();
        return t.length() > 400 ? t.substring(0, 397) + "..." : t;
    }

    public static Optional<String> extractPlan(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PLAN_SECTION.matcher(assistantText);
        if (m.find()) {
            String body = m.group(1) != null ? m.group(1).strip() : "";
            return body.isBlank() ? Optional.empty() : Optional.of(body);
        }
        String fallback = assistantText.strip();
        return fallback.isBlank() ? Optional.empty() : Optional.of(fallback);
    }

    /**
     * Optional plan revision embedded in later assistant turns.
     */
    public static Optional<String> extractUpdatedPlan(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return Optional.empty();
        }
        Matcher m = UPDATED_PLAN_SECTION.matcher(assistantText);
        if (!m.find()) {
            return Optional.empty();
        }
        String body = m.group(1) != null ? m.group(1).strip() : "";
        return body.isBlank() ? Optional.empty() : Optional.of(body);
    }

    public static String reactStepUserPrompt(String workingPlan, int stepIndex, int maxSteps) {
        String base =
                """
                        Step %d / %d — continue the ReAct loop.
                        Current Plan (may be revised via ### Updated Plan in your Thought):
                        %s

                        If a tool is needed, emit Thought then Action JSON. If done, emit FINAL_ANSWER."""
                        .formatted(stepIndex, maxSteps, workingPlan == null ? "(none)" : workingPlan)
                        .strip();
        if (stepIndex == 1) {
            return base + System.lineSeparator() + System.lineSeparator() + STEP1_USER_PLAN_ACTION_SYNC.strip();
        }
        return base;
    }

    /**
     * 模型回复中出现计划标题但本轮未解析出工具时，用于拦截死胡同式「只计划不行动」。
     */
    public static boolean assistantLooksLikePlanWithoutAction(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return false;
        }
        return assistantText.contains("### Plan") || assistantText.contains("### Updated Plan");
    }

    /** 写入会话记忆，催促模型在同一条回复中补全 ### Action。 */
    public static String emptyActionNudgeUserMessage() {
        return "你已经制定了计划，请立即根据计划输出对应的 ### Action: 指令，不要进行多余的说明。";
    }

    /**
     * Observation envelope pushed into {@link org.springframework.ai.chat.memory.ChatMemory} as a user message.
     */
    public static String observationEnvelope(String toolName, String observationBody) {
        String body = observationBody == null ? "" : observationBody.strip();
        return ("Observation (tool `%s` executed):%n%s").formatted(toolName, body);
    }
}
