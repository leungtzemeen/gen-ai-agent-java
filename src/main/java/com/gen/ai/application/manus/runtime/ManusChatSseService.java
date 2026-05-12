package com.gen.ai.application.manus.runtime;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusRunResult;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator;
import com.gen.ai.application.manus.policy.RagParticipationPolicy;
import com.gen.ai.common.exception.SensitivePromptException;
import com.gen.ai.infrastructure.security.SensitiveWordService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Phase 4：Manus 模式 HTTP/SSE 出口；每请求自建编排器 + JSON SSE Sink，避免与全局
 * {@link com.gen.ai.application.manus.api.ManusStepEventSink} Bean 串线。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class ManusChatSseService {

    private final SensitiveWordService sensitiveWordService;
    private final ManusBrainResolver manusBrainResolver;
    private final ManusStepExecutor manusStepExecutor;
    private final RagParticipationPolicy ragParticipationPolicy;
    private final ObjectMapper objectMapper;

    /**
     * @param maxSteps 外层步上限，与 {@link ManusRunRequest} 一致，非法或过小则回退为 5
     */
    public Flux<ServerSentEvent<String>> stream(
            String prompt, String sessionId, String category, int maxSteps) {
        String cleaned = prompt == null ? "" : prompt.strip();
        if (sensitiveWordService.containsSensitiveWord(cleaned)) {
            log.warn(">>>> [Security] Manus 模式检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }
        if (cleaned.isBlank()) {
            return Flux.error(new IllegalArgumentException("prompt must not be blank"));
        }
        String chatId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        int steps = maxSteps >= 1 ? maxSteps : 5;
        ManusRunRequest request = new ManusRunRequest(cleaned, chatId, category, steps);

        return Flux.<ServerSentEvent<String>>create(
                        sink -> {
                            try {
                                JsonSseManusStepEventSink jsonSink =
                                        new JsonSseManusStepEventSink(sink::next, objectMapper);
                                LoggingManusStepEventSink loggingSink = new LoggingManusStepEventSink(jsonSink);
                                DefaultManusOrchestrator orchestrator =
                                        new DefaultManusOrchestrator(
                                                manusBrainResolver,
                                                manusStepExecutor,
                                                loggingSink,
                                                ragParticipationPolicy);
                                ManusRunResult result = orchestrator.run(request);
                                String doneJson =
                                        objectMapper.writeValueAsString(
                                                new ManusDoneEventDto(
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
                                sink.error(new IllegalStateException("Manus SSE: done payload serialize failed", e));
                            } catch (Throwable t) {
                                sink.error(t);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
