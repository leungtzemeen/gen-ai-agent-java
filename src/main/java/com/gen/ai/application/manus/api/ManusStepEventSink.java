package com.gen.ai.application.manus.api;

/**
 * 消费 {@link ManusStepEvent}：Phase 4 可接 SSE；单测可用 List 收集；生产可组合 Logging 装饰器。
 */
@FunctionalInterface
public interface ManusStepEventSink {

    void onEvent(ManusStepEvent event);
}
