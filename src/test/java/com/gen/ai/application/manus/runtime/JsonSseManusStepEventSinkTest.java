package com.gen.ai.application.manus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusTerminationReason;

class JsonSseManusStepEventSinkTest {

    @Test
    void emitsPlanSnippetJson() throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<ServerSentEvent<String>> out = new ArrayList<>();
        var sink = new JsonSseManusStepEventSink(out::add, om);

        sink.onEvent(
                ManusStepEvent.planSnippet("将先检索知识库再组织回答")
                        .withRunTelemetry("trace-plan", Optional.of("deepseek")));
        sink.onEvent(
                ManusStepEvent.runFinished("bye", ManusTerminationReason.MODEL_DONE)
                        .withRunTelemetry("trace-plan", Optional.of("deepseek")));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).event()).isEqualTo("manus");
        assertThat(out.get(0).data())
                .contains("PLAN_SNIPPET")
                .contains("\"messageType\":\"PLAN_SNIPPET\"")
                .contains("\"traceId\":\"trace-plan\"")
                .contains("\"activeBrainTag\":\"deepseek\"");
        assertThat(out.get(1).data()).contains("RUN_FINISHED").contains("MODEL_DONE").contains("trace-plan");
    }

    @Test
    void emitsNamedEventWithJsonPayload() throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<ServerSentEvent<String>> out = new ArrayList<>();
        var sink = new JsonSseManusStepEventSink(out::add, om);

        sink.onEvent(
                ManusStepEvent.stepStarted(1, "hello", true)
                        .withRunTelemetry("tid-sse", Optional.empty()));
        sink.onEvent(
                ManusStepEvent.runFinished("bye", ManusTerminationReason.MODEL_DONE)
                        .withRunTelemetry("tid-sse", Optional.empty()));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).event()).isEqualTo("manus");
        assertThat(out.get(0).data())
                .contains("STEP_STARTED")
                .contains("\"stepIndex\":1")
                .contains("\"ragOn\":true")
                .contains("\"messageType\":\"META\"")
                .contains("\"traceId\":\"tid-sse\"");
        assertThat(out.get(1).data()).contains("RUN_FINISHED").contains("MODEL_DONE");
    }
}
