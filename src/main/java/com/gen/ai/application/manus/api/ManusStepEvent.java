package com.gen.ai.application.manus.api;

import java.util.Optional;

/**
 * 单条「可观测」事件：供 UI / SSE 展示，**不**进入 ChatMemory。
 *
 * @param phase                 事件阶段
 * @param stepIndex             从 1 开始；RUN_STARTED / RUN_FINISHED 可为 empty
 * @param summary               人类可读一行摘要（日志、前端气泡）
 * @param toolHint              可选：本步关联的工具名（逗号分隔）等，便于排查
 * @param ragOn                 本步是否按策略启用 RAG（STEP_STARTED / STEP_OUTCOME 时有值）
 * @param latencyMs             可选：本步执行耗时毫秒（由 {@link ManusStepExecutor} 填入 STEP_OUTCOME）
 * @param messageType           载荷类别，便于前端分区渲染
 * @param hasPendingToolCalls   可选：本步返回后是否仍声明需要工具调用（与 Spring AI {@code ChatResponse#hasToolCalls()} 对齐）
 */
public record ManusStepEvent(
        ManusStepPhase phase,
        Optional<Integer> stepIndex,
        String summary,
        Optional<String> toolHint,
        Optional<Boolean> ragOn,
        Optional<Long> latencyMs,
        Optional<ManusStepMessageType> messageType,
        Optional<Boolean> hasPendingToolCalls) {

    public static ManusStepEvent runStarted(String summary) {
        return new ManusStepEvent(
                ManusStepPhase.RUN_STARTED,
                Optional.empty(),
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ManusStepMessageType.META),
                Optional.empty());
    }

    /** Phase B：首步前的计划/任务理解文本（仅可观测，不进 Memory）。 */
    public static ManusStepEvent planSnippet(String summary) {
        return new ManusStepEvent(
                ManusStepPhase.PLAN_SNIPPET,
                Optional.empty(),
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ManusStepMessageType.PLAN_SNIPPET),
                Optional.empty());
    }

    /** 兼容旧调用方：{@code ragOn} 默认为 {@code false}。 */
    public static ManusStepEvent stepStarted(int stepIndex, String summary) {
        return stepStarted(stepIndex, summary, false);
    }

    public static ManusStepEvent stepStarted(int stepIndex, String summary, boolean ragOn) {
        return new ManusStepEvent(
                ManusStepPhase.STEP_STARTED,
                Optional.of(stepIndex),
                summary,
                Optional.empty(),
                Optional.of(ragOn),
                Optional.empty(),
                Optional.of(ManusStepMessageType.META),
                Optional.empty());
    }

    public static ManusStepEvent stepOutcome(
            int stepIndex,
            String summary,
            Optional<String> toolHint,
            boolean ragOn,
            Optional<Long> latencyMs,
            ManusStepMessageType messageType,
            Optional<Boolean> hasPendingToolCalls) {
        return new ManusStepEvent(
                ManusStepPhase.STEP_OUTCOME,
                Optional.of(stepIndex),
                summary,
                toolHint,
                Optional.of(ragOn),
                latencyMs,
                Optional.of(messageType),
                hasPendingToolCalls);
    }

    public static ManusStepEvent runFinished(String summary, ManusTerminationReason reason) {
        String withReason = summary + " [" + reason + "]";
        return new ManusStepEvent(
                ManusStepPhase.RUN_FINISHED,
                Optional.empty(),
                withReason,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ManusStepMessageType.META),
                Optional.empty());
    }
}
