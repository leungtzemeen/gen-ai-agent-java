package com.gen.ai.application.manus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

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

        sink.onEvent(ManusStepEvent.planSnippet("将先检索知识库再组织回答"));
        sink.onEvent(ManusStepEvent.runFinished("bye", ManusTerminationReason.MODEL_DONE));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).event()).isEqualTo("manus");
        assertThat(out.get(0).data())
                .contains("PLAN_SNIPPET")
                .contains("\"messageType\":\"PLAN_SNIPPET\"");
        assertThat(out.get(1).data()).contains("RUN_FINISHED").contains("MODEL_DONE");
    }

    @Test
    void emitsNamedEventWithJsonPayload() throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<ServerSentEvent<String>> out = new ArrayList<>();
        var sink = new JsonSseManusStepEventSink(out::add, om);

        sink.onEvent(ManusStepEvent.stepStarted(1, "hello", true));
        sink.onEvent(ManusStepEvent.runFinished("bye", ManusTerminationReason.MODEL_DONE));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).event()).isEqualTo("manus");
        assertThat(out.get(0).data())
                .contains("STEP_STARTED")
                .contains("\"stepIndex\":1")
                .contains("\"ragOn\":true")
                .contains("\"messageType\":\"META\"");
        assertThat(out.get(1).data()).contains("RUN_FINISHED").contains("MODEL_DONE");
    }
}
