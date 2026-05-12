package com.gen.ai.application.minus.api;

import java.util.Optional;

/**
 * 单条「可观测」事件：供 UI / SSE 展示，**不**进入 ChatMemory。
 *
 * @param phase        事件阶段
 * @param stepIndex    从 1 开始；RUN_STARTED / RUN_FINISHED 可为 empty
 * @param summary      人类可读一行摘要（日志、前端气泡）
 * @param toolHint     可选：本步关联的工具名或占位，便于排查
 */
public record MinusStepEvent(
        MinusStepPhase phase,
        Optional<Integer> stepIndex,
        String summary,
        Optional<String> toolHint) {

    public static MinusStepEvent runStarted(String summary) {
        return new MinusStepEvent(MinusStepPhase.RUN_STARTED, Optional.empty(), summary, Optional.empty());
    }

    public static MinusStepEvent stepStarted(int stepIndex, String summary) {
        return new MinusStepEvent(
                MinusStepPhase.STEP_STARTED, Optional.of(stepIndex), summary, Optional.empty());
    }

    public static MinusStepEvent stepOutcome(int stepIndex, String summary, Optional<String> toolHint) {
        return new MinusStepEvent(MinusStepPhase.STEP_OUTCOME, Optional.of(stepIndex), summary, toolHint);
    }

    public static MinusStepEvent runFinished(String summary, MinusTerminationReason reason) {
        String withReason = summary + " [" + reason + "]";
        return new MinusStepEvent(MinusStepPhase.RUN_FINISHED, Optional.empty(), withReason, Optional.empty());
    }
}
