package com.gen.ai.application.manus.runtime;

/**
 * Manus SSE 收尾事件 {@code event: done} 的 JSON 载体。
 */
public record ManusDoneEventDto(String finalSummary, String termination, int executedSteps) {}
