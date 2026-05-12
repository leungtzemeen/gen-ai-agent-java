package com.gen.ai.application.minus.api;

/**
 * {@link MinusStepEvent} 的阶段标签：供 SSE / 前端区分「开始执行」与「本步结果」等。
 * <p>
 * 刻意不写入 {@link org.springframework.ai.chat.memory.ChatMemory}，仅经 {@link MinusStepEventSink} 外发。
 */
public enum MinusStepPhase {

    /** 整次 Minus 任务已创建上下文（含 {@link MinusBrainResolver} 冻结结果）。 */
    RUN_STARTED,

    /** 即将执行第 k 步（尚未调用 LLM）。 */
    STEP_STARTED,

    /** 第 k 步执行结束（含本步摘要，无责任拼接完整对话历史）。 */
    STEP_OUTCOME,

    /** 整次任务结束（含 {@link MinusTerminationReason}）。 */
    RUN_FINISHED
}
