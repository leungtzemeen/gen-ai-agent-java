package com.gen.ai.application.minus.api;

/**
 * 消费 {@link MinusStepEvent}：Phase 4 可接 SSE；单测可用 List 收集；生产可组合 Logging 装饰器。
 */
@FunctionalInterface
public interface MinusStepEventSink {

    void onEvent(MinusStepEvent event);
}
