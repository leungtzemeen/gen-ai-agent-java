package com.gen.ai.application.minus.runtime;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusStepEventSink;

/**
 * 将 {@link MinusStepEvent} 序列化为 JSON，以 SSE {@code event: minus} 下发（与现网
 * {@code text/event-stream} 一致，仅增加命名事件便于前端解析）。
 */
public final class JsonSseMinusStepEventSink implements MinusStepEventSink {

    private final Consumer<ServerSentEvent<String>> downstream;
    private final ObjectMapper objectMapper;

    public JsonSseMinusStepEventSink(Consumer<ServerSentEvent<String>> downstream, ObjectMapper objectMapper) {
        this.downstream = Objects.requireNonNull(downstream);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void onEvent(MinusStepEvent event) {
        try {
            MinusStepEventDto dto = MinusStepEventDto.from(event);
            String json = objectMapper.writeValueAsString(dto);
            downstream.accept(ServerSentEvent.<String>builder().event("minus").data(json).build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Minus SSE: failed to serialize MinusStepEvent", e);
        }
    }
}
