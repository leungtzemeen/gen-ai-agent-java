package com.gen.ai.application.manus.api;

import java.util.Optional;

/**
 * 单条「可观测」事件：供 UI / SSE 展示，**不**进入 ChatMemory。
 *
 * @param phase        事件阶段
 * @param stepIndex    从 1 开始；RUN_STARTED / RUN_FINISHED 可为 empty
 * @param summary      人类可读一行摘要（日志、前端气泡）
 * @param toolHint     可选：本步关联的工具名或占位，便于排查
 */
public record ManusStepEvent(
        ManusStepPhase phase,
        Optional<Integer> stepIndex,
        String summary,
        Optional<String> toolHint) {

    public static ManusStepEvent runStarted(String summary) {
        return new ManusStepEvent(ManusStepPhase.RUN_STARTED, Optional.empty(), summary, Optional.empty());
    }

    public static ManusStepEvent stepStarted(int stepIndex, String summary) {
        return new ManusStepEvent(
                ManusStepPhase.STEP_STARTED, Optional.of(stepIndex), summary, Optional.empty());
    }

    public static ManusStepEvent stepOutcome(int stepIndex, String summary, Optional<String> toolHint) {
        return new ManusStepEvent(ManusStepPhase.STEP_OUTCOME, Optional.of(stepIndex), summary, toolHint);
    }

    public static ManusStepEvent runFinished(String summary, ManusTerminationReason reason) {
        String withReason = summary + " [" + reason + "]";
        return new ManusStepEvent(ManusStepPhase.RUN_FINISHED, Optional.empty(), withReason, Optional.empty());
    }
}
