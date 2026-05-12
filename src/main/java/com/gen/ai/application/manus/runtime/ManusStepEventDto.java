package com.gen.ai.application.manus.runtime;

import com.gen.ai.application.manus.api.ManusStepEvent;

/**
 * SSE JSON 载体：与 {@link ManusStepEvent} 一一对应，字段均为 Jackson 友好类型。
 */
public record ManusStepEventDto(String phase, Integer stepIndex, String summary, String toolHint) {

    static ManusStepEventDto from(ManusStepEvent event) {
        return new ManusStepEventDto(
                event.phase().name(),
                event.stepIndex().orElse(null),
                event.summary(),
                event.toolHint().orElse(null));
    }
}
