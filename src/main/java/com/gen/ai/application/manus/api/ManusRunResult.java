package com.gen.ai.application.manus.api;

/**
 * 一次 Manus 任务的输出（整段编排结束后返回给上层 / SSE 收尾）。
 *
 * @param finalSummary   面向用户的最终可见摘要（Phase 3 可为模型最后一段文本）
 * @param termination    结束原因
 * @param executedSteps  实际执行的外层步数（不含未开始的步）
 */
public record ManusRunResult(String finalSummary, ManusTerminationReason termination, int executedSteps) {}
