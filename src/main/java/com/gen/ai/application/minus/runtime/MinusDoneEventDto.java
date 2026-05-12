package com.gen.ai.application.minus.runtime;

/**
 * Minus SSE 收尾事件 {@code event: done} 的 JSON 载体。
 */
public record MinusDoneEventDto(String finalSummary, String termination, int executedSteps) {}
