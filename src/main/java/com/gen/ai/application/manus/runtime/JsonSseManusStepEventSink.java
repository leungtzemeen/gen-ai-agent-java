package com.gen.ai.application.manus.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

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

    private final ManusSseManusJsonConsumer jsonConsumer;
    private final ObjectMapper objectMapper;

    public JsonSseManusStepEventSink(ManusSseManusJsonConsumer jsonConsumer, ObjectMapper objectMapper) {
        this.jsonConsumer = Objects.requireNonNull(jsonConsumer);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * 测试或适配 {@link ServerSentEvent} 流时，将每帧 JSON 包进 {@code event: manus} 语义等价的结构。
     */
    public static JsonSseManusStepEventSink forServerSentEventTest(
            java.util.function.Consumer<ServerSentEvent<String>> downstream, ObjectMapper objectMapper) {
        return new JsonSseManusStepEventSink(
                json ->
                        downstream.accept(
                                ServerSentEvent.<String>builder().event("manus").data(json).build()),
                objectMapper);
    }

    @Override
    public void onEvent(ManusStepEvent event) {
        try {
            ManusStepEventDto dto = ManusStepEventDto.from(event);
            String json = objectMapper.writeValueAsString(dto);
            jsonConsumer.accept(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Manus SSE: failed to serialize ManusStepEvent", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
