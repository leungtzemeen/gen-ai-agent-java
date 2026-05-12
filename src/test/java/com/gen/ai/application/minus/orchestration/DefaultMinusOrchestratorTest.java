package com.gen.ai.application.minus.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusRunContext;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusRunResult;
import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusStepEventSink;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.api.MinusStepOutcome;
import com.gen.ai.application.minus.api.MinusStepPhase;
import com.gen.ai.application.minus.api.MinusTerminationReason;
import com.gen.ai.application.minus.policy.FirstStepOnlyRagPolicy;
import com.gen.ai.application.minus.runtime.PlaceholderMinusBrainResolver;
import com.gen.ai.application.minus.runtime.PlaceholderMinusChatRuntime;

class DefaultMinusOrchestratorTest {

    @Test
    void finishesEarlyWhenExecutorDeclaresDone() {
        List<MinusStepEvent> events = new ArrayList<>();
        MinusStepEventSink sink = events::add;

        MinusStepExecutor executor =
                (MinusRunContext ctx, int step) ->
                        step >= 2
                                ? MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, "step2-done")
                                : MinusStepOutcome.continueRun("step" + step + "-continue");

        DefaultMinusOrchestrator orchestrator =
                new DefaultMinusOrchestrator(
                        new PlaceholderMinusBrainResolver(), executor, sink, new FirstStepOnlyRagPolicy());

        MinusRunResult result =
                orchestrator.run(new MinusRunRequest("hello", "chat-a", "手机", 10));

        assertThat(result.termination()).isEqualTo(MinusTerminationReason.MODEL_DONE);
        assertThat(result.executedSteps()).isEqualTo(2);
        assertThat(result.finalSummary()).isEqualTo("step2-done");

        assertThat(events).extracting(MinusStepEvent::phase).containsExactly(
                MinusStepPhase.RUN_STARTED,
                MinusStepPhase.STEP_STARTED,
                MinusStepPhase.STEP_OUTCOME,
                MinusStepPhase.STEP_STARTED,
                MinusStepPhase.STEP_OUTCOME,
                MinusStepPhase.RUN_FINISHED);
    }

    @Test
    void hitsMaxStepsWhenExecutorNeverFinishes() {
        List<MinusStepEvent> events = new ArrayList<>();
        MinusStepExecutor executor =
                (ctx, step) -> MinusStepOutcome.continueRun("still-working-" + step);

        DefaultMinusOrchestrator orchestrator =
                new DefaultMinusOrchestrator(
                        new PlaceholderMinusBrainResolver(), executor, events::add, new FirstStepOnlyRagPolicy());

        MinusRunResult result = orchestrator.run(new MinusRunRequest("x", "chat-b", null, 3));

        assertThat(result.termination()).isEqualTo(MinusTerminationReason.MAX_STEPS);
        assertThat(result.executedSteps()).isEqualTo(3);
        assertThat(events.stream().filter(e -> e.phase() == MinusStepPhase.RUN_FINISHED))
                .hasSize(1);
    }

    @Test
    void brainResolverResolveCalledExactlyOnce() {
        AtomicInteger resolveCount = new AtomicInteger();
        MinusBrainResolver counting =
                req -> {
                    resolveCount.incrementAndGet();
                    return new PlaceholderMinusChatRuntime("singleton-runtime");
                };

        MinusStepExecutor executor =
                (ctx, step) -> {
                    assertThat(ctx.chatRuntime().runtimeDebugId()).isEqualTo("singleton-runtime");
                    return step >= 1
                            ? MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, "ok")
                            : MinusStepOutcome.continueRun("n/a");
                };

        DefaultMinusOrchestrator orchestrator =
                new DefaultMinusOrchestrator(counting, executor, e -> {}, new FirstStepOnlyRagPolicy());

        orchestrator.run(new MinusRunRequest("q", "c", null, 5));

        assertThat(resolveCount.get()).isEqualTo(1);
    }
}
