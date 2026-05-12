package com.gen.ai.application.manus.api;

/**
 * {@link ManusStepEvent} 的阶段标签：供 SSE / 前端区分「开始执行」与「本步结果」等。
 * <p>
 * 刻意不写入 {@link org.springframework.ai.chat.memory.ChatMemory}，仅经 {@link ManusStepEventSink} 外发。
 */
public enum ManusStepPhase {

    /** 整次 Manus 任务已创建上下文（含 {@link ManusBrainResolver} 冻结结果）。 */
    RUN_STARTED,

    /**
     * Phase B：首步前由 {@link ManusPlanner} 生成的计划/任务理解摘要（仅 SSE，不进 Memory）。
     */
    PLAN_SNIPPET,

    /** 即将执行第 k 步（尚未调用 LLM）。 */
    STEP_STARTED,

    /** 第 k 步执行结束（含本步摘要，无责任拼接完整对话历史）。 */
    STEP_OUTCOME,

    /** 整次任务结束（含 {@link ManusTerminationReason}）。 */
    RUN_FINISHED
}
