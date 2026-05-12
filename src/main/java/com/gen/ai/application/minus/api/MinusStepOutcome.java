package com.gen.ai.application.minus.api;

import java.util.Optional;

/**
 * 单步执行结果：由 {@link MinusStepExecutor} 返回，供 {@link com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator} 决定是否结束外层循环。
 *
 * @param finishedRun         若为 true，编排层在本步后立即结束并带上 {@code terminationReason}
 * @param terminationReason   仅当 {@code finishedRun} 为 true 时有意义
 * @param stepSummaryForUi    本步可展示摘要（写入 {@link MinusStepEvent}，不进 Memory）
 */
public record MinusStepOutcome(
        boolean finishedRun,
        Optional<MinusTerminationReason> terminationReason,
        String stepSummaryForUi) {

    public static MinusStepOutcome continueRun(String stepSummaryForUi) {
        return new MinusStepOutcome(false, Optional.empty(), stepSummaryForUi);
    }

    public static MinusStepOutcome finish(MinusTerminationReason reason, String stepSummaryForUi) {
        return new MinusStepOutcome(true, Optional.of(reason), stepSummaryForUi);
    }
}
