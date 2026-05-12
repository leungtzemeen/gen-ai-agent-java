package com.gen.ai.application.manus.runtime;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;

/**
 * 将 {@link ManusStepEvent} 序列化为 JSON，以 SSE {@code event: manus} 下发（与现网
 * {@code text/event-stream} 一致，仅增加命名事件便于前端解析）。
 */
public final class JsonSseManusStepEventSink implements ManusStepEventSink {

    private final Consumer<ServerSentEvent<String>> downstream;
    private final ObjectMapper objectMapper;

    public JsonSseManusStepEventSink(Consumer<ServerSentEvent<String>> downstream, ObjectMapper objectMapper) {
        this.downstream = Objects.requireNonNull(downstream);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void onEvent(ManusStepEvent event) {
        try {
            ManusStepEventDto dto = ManusStepEventDto.from(event);
            String json = objectMapper.writeValueAsString(dto);
            downstream.accept(ServerSentEvent.<String>builder().event("manus").data(json).build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Manus SSE: failed to serialize ManusStepEvent", e);
        }
    }
}
