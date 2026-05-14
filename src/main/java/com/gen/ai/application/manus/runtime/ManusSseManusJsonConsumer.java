package com.gen.ai.application.manus.runtime;

import java.io.IOException;

/**
 * 接收已序列化的 Manus 步事件 JSON 字符串，由 {@link JsonSseManusStepEventSink} 调用；
 * 实现方负责以 SSE {@code event: manus} 写出（例如 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}）。
 */
@FunctionalInterface
public interface ManusSseManusJsonConsumer {

    void accept(String manusEventJson) throws IOException;
}
