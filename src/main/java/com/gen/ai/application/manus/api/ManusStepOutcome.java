package com.gen.ai.application.manus.api;

import java.util.Optional;

/**
 * 单步执行结果：由 {@link ManusStepExecutor} 返回，供 {@link com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator} 决定是否结束外层循环。
 *
 * @param finishedRun              若为 true，编排层在本步后立即结束并带上 {@code terminationReason}
 * @param terminationReason        仅当 {@code finishedRun} 为 true 时有意义
 * @param stepSummaryForUi         本步可展示摘要（写入 {@link ManusStepEvent}，不进 Memory）
 * @param stepLatencyMillis        可选：本步一次 {@code ChatClient} 调用耗时（毫秒）
 * @param hasPendingToolCalls      可选：本步返回后模型是否仍声明需要工具调用
 * @param toolHintForObservers     可选：工具名摘要（如逗号分隔），供 SSE / 日志
 */
public record ManusStepOutcome(
        boolean finishedRun,
        Optional<ManusTerminationReason> terminationReason,
        String stepSummaryForUi,
        Optional<Long> stepLatencyMillis,
        Optional<Boolean> hasPendingToolCalls,
        Optional<String> toolHintForObservers) {

    public static ManusStepOutcome continueRun(String stepSummaryForUi) {
        return continueRun(
                stepSummaryForUi, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ManusStepOutcome continueRun(
            String stepSummaryForUi,
            Optional<Long> stepLatencyMillis,
            Optional<Boolean> hasPendingToolCalls,
            Optional<String> toolHintForObservers) {
        return new ManusStepOutcome(
                false,
                Optional.empty(),
                stepSummaryForUi,
                stepLatencyMillis,
                hasPendingToolCalls,
                toolHintForObservers);
    }

    public static ManusStepOutcome finish(ManusTerminationReason reason, String stepSummaryForUi) {
        return finish(
                reason,
                stepSummaryForUi,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static ManusStepOutcome finish(
            ManusTerminationReason reason,
            String stepSummaryForUi,
            Optional<Long> stepLatencyMillis,
            Optional<Boolean> hasPendingToolCalls,
            Optional<String> toolHintForObservers) {
        return new ManusStepOutcome(
                true,
                Optional.of(reason),
                stepSummaryForUi,
                stepLatencyMillis,
                hasPendingToolCalls,
                toolHintForObservers);
    }
}
