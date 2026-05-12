package com.gen.ai.application.minus.runtime;

import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusStepEventSink;

/**
 * 无下游时的空 Sink（单测或仅日志场景可配合 {@link LoggingMinusStepEventSink} 使用）。
 */
public enum NoOpMinusStepEventSink implements MinusStepEventSink {
    INSTANCE;

    @Override
    public void onEvent(MinusStepEvent event) {
        // intentionally empty
    }
}
