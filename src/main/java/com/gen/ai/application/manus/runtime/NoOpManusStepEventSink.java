package com.gen.ai.application.manus.runtime;

import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;

/**
 * 无下游时的空 Sink（单测或仅日志场景可配合 {@link LoggingManusStepEventSink} 使用）。
 */
public enum NoOpManusStepEventSink implements ManusStepEventSink {
    INSTANCE;

    @Override
    public void onEvent(ManusStepEvent event) {
        // intentionally empty
    }
}
