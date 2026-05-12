package com.gen.ai.application.minus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusTerminationReason;

class JsonSseMinusStepEventSinkTest {

    @Test
    void emitsNamedEventWithJsonPayload() throws Exception {
        ObjectMapper om = new ObjectMapper();
        List<ServerSentEvent<String>> out = new ArrayList<>();
        var sink = new JsonSseMinusStepEventSink(out::add, om);

        sink.onEvent(MinusStepEvent.stepStarted(1, "hello"));
        sink.onEvent(MinusStepEvent.runFinished("bye", MinusTerminationReason.MODEL_DONE));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).event()).isEqualTo("minus");
        assertThat(out.get(0).data()).contains("STEP_STARTED").contains("\"stepIndex\":1");
        assertThat(out.get(1).data()).contains("RUN_FINISHED").contains("MODEL_DONE");
    }
}
