package com.gen.ai.application.manus.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusPlanner;
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

    /** 外层步上限，与 {@code wiselink.manus.max-steps} 对齐；非法值在 {@link #stream} 内回退为 5 */
    @Value("${wiselink.manus.max-steps:5}")
    private int manusMaxSteps;

    private final SensitiveWordService sensitiveWordService;
    private final ManusBrainResolver manusBrainResolver;
    private final ManusStepExecutor manusStepExecutor;
    private final RagParticipationPolicy ragParticipationPolicy;
    private final ManusPlanner manusPlanner;
    private final ObjectMapper objectMapper;

    /**
     * Manus SSE：仅接受用户文案与会话 id；外层步上限取自 {@code wiselink.manus.max-steps}；不按 HTTP 传
     * {@code biz_category}（RAG 不按类目过滤）。
     */
    public Flux<ServerSentEvent<String>> stream(String prompt, String sessionId) {
        String cleaned = prompt == null ? "" : prompt.strip();
        if (sensitiveWordService.containsSensitiveWord(cleaned)) {
            log.warn(">>>> [Security] Manus 模式检测到敏感提问，已在本地拦截");
            return Flux.error(new SensitivePromptException());
        }
        if (cleaned.isBlank()) {
            return Flux.error(new IllegalArgumentException("prompt must not be blank"));
        }
        String chatId = sessionId == null ? "" : sessionId.strip();
        if (chatId.isBlank()) {
            return Flux.error(new IllegalArgumentException("sessionId must not be blank"));
        }
        int steps = manusMaxSteps >= 1 ? manusMaxSteps : 5;
        ManusRunRequest request = new ManusRunRequest(cleaned, chatId, null, steps);

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
                                                ragParticipationPolicy,
                                                manusPlanner);
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
