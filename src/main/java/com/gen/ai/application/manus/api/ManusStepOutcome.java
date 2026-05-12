package com.gen.ai.application.manus.api;

import java.util.Optional;

/**
 * 单步执行结果：由 {@link ManusStepExecutor} 返回，供 {@link com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator} 决定是否结束外层循环。
 *
 * @param finishedRun         若为 true，编排层在本步后立即结束并带上 {@code terminationReason}
 * @param terminationReason   仅当 {@code finishedRun} 为 true 时有意义
 * @param stepSummaryForUi    本步可展示摘要（写入 {@link ManusStepEvent}，不进 Memory）
 */
public record ManusStepOutcome(
        boolean finishedRun,
        Optional<ManusTerminationReason> terminationReason,
        String stepSummaryForUi) {

    public static ManusStepOutcome continueRun(String stepSummaryForUi) {
        return new ManusStepOutcome(false, Optional.empty(), stepSummaryForUi);
    }

    public static ManusStepOutcome finish(ManusTerminationReason reason, String stepSummaryForUi) {
        return new ManusStepOutcome(true, Optional.of(reason), stepSummaryForUi);
    }
}
