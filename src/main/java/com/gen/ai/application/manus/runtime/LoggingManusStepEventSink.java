package com.gen.ai.application.manus.runtime;

import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 装饰器：在事件外发前后打结构化日志，便于线上对照 SSE 与「步间是否换脑」类问题。
 * <p>
 * 可与任意下游 {@link ManusStepEventSink}（如 SSE、List 收集器）组合。
 */
@Slf4j
@RequiredArgsConstructor
public final class LoggingManusStepEventSink implements ManusStepEventSink {

    private final ManusStepEventSink delegate;

    @Override
    public void onEvent(ManusStepEvent event) {
        log.info(
                ">>>> [Manus-StepEvent] phase={} stepIndex={} messageType={} ragOn={} latencyMs={} pendingTools={} toolHint={} summary={}",
                event.phase(),
                event.stepIndex().map(String::valueOf).orElse("-"),
                event.messageType().map(Enum::name).orElse("-"),
                event.ragOn().map(String::valueOf).orElse("-"),
                event.latencyMs().map(String::valueOf).orElse("-"),
                event.hasPendingToolCalls().map(String::valueOf).orElse("-"),
                event.toolHint().orElse("-"),
                event.summary());
        delegate.onEvent(event);
    }
}
