package com.gen.ai.application.minus.runtime;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusRunResult;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator;
import com.gen.ai.application.minus.policy.RagParticipationPolicy;
import com.gen.ai.common.exception.SensitivePromptException;
import com.gen.ai.infrastructure.security.SensitiveWordService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Phase 4：Minus 模式 HTTP/SSE 出口；每请求自建编排器 + JSON SSE Sink，避免与全局
 * {@link com.gen.ai.application.minus.api.MinusStepEventSink} Bean 串线。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class MinusChatSseService {

    private final SensitiveWordService sensitiveWordService;
    private final MinusBrainResolver minusBrainResolver;
    private final MinusStepExecutor minusStepExecutor;
    private final RagParticipationPolicy ragParticipationPolicy;
    private final ObjectMapper objectMapper;

    /**
     * @param maxSteps 外层步上限，与 {@link MinusRunRequest} 一致，非法或过小则回退为 5
     */
    public Flux<ServerSentEvent<String>> stream(
            String prompt, String sessionId, String category, int maxSteps) {
        String cleaned = prompt == null ? "" : prompt.strip();
        if (sensitiveWordService.containsSensitiveWord(cleaned)) {
            log.warn(">>>> [Security] Minus 模式检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }
        if (cleaned.isBlank()) {
            return Flux.error(new IllegalArgumentException("prompt must not be blank"));
        }
        String chatId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        int steps = maxSteps >= 1 ? maxSteps : 5;
        MinusRunRequest request = new MinusRunRequest(cleaned, chatId, category, steps);

        return Flux.<ServerSentEvent<String>>create(
                        sink -> {
                            try {
                                JsonSseMinusStepEventSink jsonSink =
                                        new JsonSseMinusStepEventSink(sink::next, objectMapper);
                                LoggingMinusStepEventSink loggingSink = new LoggingMinusStepEventSink(jsonSink);
                                DefaultMinusOrchestrator orchestrator =
                                        new DefaultMinusOrchestrator(
                                                minusBrainResolver,
                                                minusStepExecutor,
                                                loggingSink,
                                                ragParticipationPolicy);
                                MinusRunResult result = orchestrator.run(request);
                                String doneJson =
                                        objectMapper.writeValueAsString(
                                                new MinusDoneEventDto(
                                                        result.finalSummary(),
                                                        result.termination().name(),
                                                        result.executedSteps()));
                                sink.next(
                                        ServerSentEvent.<String>builder()
                                                .event("done")
                                                .data(doneJson)
                                                .build());
                                sink.complete();
                            } catch (JsonProcessingException e) {
                                sink.error(new IllegalStateException("Minus SSE: done payload serialize failed", e));
                            } catch (Throwable t) {
                                sink.error(t);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
