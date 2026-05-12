package com.gen.ai.application.minus.runtime;

import com.gen.ai.application.minus.api.MinusStepEvent;

/**
 * SSE JSON 载体：与 {@link MinusStepEvent} 一一对应，字段均为 Jackson 友好类型。
 */
public record MinusStepEventDto(String phase, Integer stepIndex, String summary, String toolHint) {

    static MinusStepEventDto from(MinusStepEvent event) {
        return new MinusStepEventDto(
                event.phase().name(),
                event.stepIndex().orElse(null),
                event.summary(),
                event.toolHint().orElse(null));
    }
}
