package com.gen.ai.application.minus.runtime;

import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusStepEventSink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 装饰器：在事件外发前后打结构化日志，便于线上对照 SSE 与「步间是否换脑」类问题。
 * <p>
 * 可与任意下游 {@link MinusStepEventSink}（如 SSE、List 收集器）组合。
 */
@Slf4j
@RequiredArgsConstructor
public final class LoggingMinusStepEventSink implements MinusStepEventSink {

    private final MinusStepEventSink delegate;

    @Override
    public void onEvent(MinusStepEvent event) {
        log.info(
                ">>>> [Minus-StepEvent] phase={} stepIndex={} summary={} toolHint={}",
                event.phase(),
                event.stepIndex().map(String::valueOf).orElse("-"),
                event.summary(),
                event.toolHint().orElse("-"));
        delegate.onEvent(event);
    }
}
