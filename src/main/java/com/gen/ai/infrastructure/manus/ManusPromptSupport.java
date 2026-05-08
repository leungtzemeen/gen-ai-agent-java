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

    /**
     * 拼入 {@link com.gen.ai.infrastructure.manus.WiseLinkManusEngine} 的 {@code planningSystem}（人设 + 本段）。
     * 硬核「Step 内强制结案」见 {@link #MANUS_FORCE_CLOSE_APPEND}，仅挂在 ReAct 系统提示，避免 Planning 重复耗 token。
     */
    static final String PLANNING_APPEND =
            """

                    ### Planning phase (required first)

                    【强制结案与效率优先】在制定计划时：**严禁**设计超过 **4** 步的冗余计划。一旦通过工具获得**核心数据**（价格、销量、规格、库存摘要等），**下一步必须**规划为 **[FINAL_ANSWER]** 收口步骤，不得再插入「再验证」「再搜一轮」「重复同一工具」等步骤。

                    【数据充足即结案】若任务是**比价 / 查价**：只需 **[搜索]** + **[分析对比]** + **[输出结果]** 三步骨架即可。**多品对比必须一箭双雕**：在**单次** getProductRealtimeStatus 的 `q`/`productName` 中合并全部对比对象（如「华为XX 对比 小米XX」），**禁止**用同一关键词连续两次调用该工具。合计仍 **≤4** 步。**禁止**「验证缓存」「多轮确认」「二次核对源站」等步骤；在当前资源下此类行为一律违规。

                    Before any tools, write a concise numbered plan under markdown heading ### Plan (**最多 4 步**，每一步一行、可执行、无废话). The **final** step must be 输出 **FINAL_ANSWER** / 自然语言总结给用户。
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
                    - Keep Thought short when calling tools; expand in FINAL_ANSWER.

                    Product / price / stock / web comparison (导购「查商品」):
                    - Prefer the single unified tool **getProductRealtimeStatus** with `action_input` as a JSON object, e.g. {"productName":"..."} or {"q":"..."} (VIP session may be required). It covers public-web summaries relevant to price, availability, and reviews.
                    - Do not assume legacy tools **getProductPriceFunction** or **getProductStockFunction** exist — they have been removed.

                    【禁止重复探测】：严禁连续两次调用同一个工具查询同一个关键词。如果你上一步已经获得了 Observation，本步必须进行数据处理或结案，绝对不允许再次调用 getProductRealtimeStatus！

                    【合并检索（一箭双雕）】对比多款商品（如华为+小米）时，必须在**一次** getProductRealtimeStatus 的 `q`/`productName` 中写入**全部对比对象**（合并查询词），从而减少反复开关工具；禁止用「先搜 A 再原样搜 A」式重复探测。

                    ### 结案准则
                    - 如果你已经通过 getProductRealtimeStatus 获得了商品的价格、销量等核心信息，请不要进行重复验证，直接输出最终答案。效率优于过度验证。
                    - 若 Observation 标明「实时检索配额已满」「正常收口」或含 [系统指令] 要求停止检索，视为已收口：基于此前 Observation 与对话输出 FINAL_ANSWER，严禁再次调用 getProductRealtimeStatus 或任何检索类工具。""";

    /** 接在 ReAct 系统提示末尾，硬核强制收官（7B 防重复 Action）。 */
    static final String MANUS_FORCE_CLOSE_APPEND =
            """

                    【强制结案指令】：如果你已经调用工具并获得了 Observation，严禁在接下来的 Step 中重复调用相同工具。你必须在下一个 Step 中直接对已有数据进行总结，并以 [FINAL_ANSWER] 标签作为回复的开头。如果遇到 [流量管控] 提示，也必须立即结案（含 Observation 中「实时检索配额已满」、[系统指令] 等同义收口表述）.""";

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
            base = base + System.lineSeparator() + System.lineSeparator() + STEP1_USER_PLAN_ACTION_SYNC.strip();
        }
        if (stepIndex >= 8) {
            base =
                    base
                            + System.lineSeparator()
                            + System.lineSeparator()
                            + "【步数预警】你已接近步数限制，必须在下一步给出 FINAL_ANSWER，否则任务将失败。";
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
