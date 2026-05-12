package com.gen.ai.application.manus.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusPlanner;
import com.gen.ai.application.manus.api.ManusRunContext;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusRunResult;
import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.api.ManusStepOutcome;
import com.gen.ai.application.manus.api.ManusStepPhase;
import com.gen.ai.application.manus.api.ManusTerminationReason;
import com.gen.ai.application.manus.policy.FirstStepOnlyRagPolicy;
import com.gen.ai.application.manus.runtime.NoOpManusPlanner;
import com.gen.ai.application.manus.runtime.PlaceholderManusBrainResolver;
import com.gen.ai.application.manus.runtime.PlaceholderManusChatRuntime;

class DefaultManusOrchestratorTest {

    @Test
    void finishesEarlyWhenExecutorDeclaresDone() {
        List<ManusStepEvent> events = new ArrayList<>();
        ManusStepEventSink sink = events::add;

        ManusStepExecutor executor =
                (ManusRunContext ctx, int step) ->
                        step >= 2
                                ? ManusStepOutcome.finish(ManusTerminationReason.MODEL_DONE, "step2-done")
                                : ManusStepOutcome.continueRun("step" + step + "-continue");

        DefaultManusOrchestrator orchestrator =
                new DefaultManusOrchestrator(
                        new PlaceholderManusBrainResolver(),
                        executor,
                        sink,
                        new FirstStepOnlyRagPolicy(),
                        NoOpManusPlanner.INSTANCE);

        ManusRunResult result =
                orchestrator.run(new ManusRunRequest("hello", "chat-a", "手机", 10));

        assertThat(result.termination()).isEqualTo(ManusTerminationReason.MODEL_DONE);
        assertThat(result.executedSteps()).isEqualTo(2);
        assertThat(result.finalSummary()).isEqualTo("step2-done");

        assertThat(events).extracting(ManusStepEvent::phase).containsExactly(
                ManusStepPhase.RUN_STARTED,
                ManusStepPhase.STEP_STARTED,
                ManusStepPhase.STEP_OUTCOME,
                ManusStepPhase.STEP_STARTED,
                ManusStepPhase.STEP_OUTCOME,
                ManusStepPhase.RUN_FINISHED);

        String traceId = events.getFirst().traceId().orElseThrow();
        assertThat(events).allMatch(e -> e.traceId().filter(traceId::equals).isPresent());
    }

    @Test
    void hitsMaxStepsWhenExecutorNeverFinishes() {
        List<ManusStepEvent> events = new ArrayList<>();
        ManusStepExecutor executor =
                (ctx, step) -> ManusStepOutcome.continueRun("still-working-" + step);

        DefaultManusOrchestrator orchestrator =
                new DefaultManusOrchestrator(
                        new PlaceholderManusBrainResolver(),
                        executor,
                        events::add,
                        new FirstStepOnlyRagPolicy(),
                        NoOpManusPlanner.INSTANCE);

        ManusRunResult result = orchestrator.run(new ManusRunRequest("x", "chat-b", null, 3));

        assertThat(result.termination()).isEqualTo(ManusTerminationReason.MAX_STEPS);
        assertThat(result.executedSteps()).isEqualTo(3);
        assertThat(events.stream().filter(e -> e.phase() == ManusStepPhase.RUN_FINISHED))
                .hasSize(1);
    }

    @Test
    void brainResolverResolveCalledExactlyOnce() {
        AtomicInteger resolveCount = new AtomicInteger();
        ManusBrainResolver counting =
                req -> {
                    resolveCount.incrementAndGet();
                    return new PlaceholderManusChatRuntime("singleton-runtime");
                };

        ManusStepExecutor executor =
                (ctx, step) -> {
                    assertThat(ctx.chatRuntime().runtimeDebugId()).isEqualTo("singleton-runtime");
                    return step >= 1
                            ? ManusStepOutcome.finish(ManusTerminationReason.MODEL_DONE, "ok")
                            : ManusStepOutcome.continueRun("n/a");
                };

        DefaultManusOrchestrator orchestrator =
                new DefaultManusOrchestrator(
                        counting, executor, e -> {}, new FirstStepOnlyRagPolicy(), NoOpManusPlanner.INSTANCE);

        orchestrator.run(new ManusRunRequest("q", "c", null, 5));

        assertThat(resolveCount.get()).isEqualTo(1);
    }

    @Test
    void emitsPlanSnippetWhenPlannerReturnsBrief() {
        List<ManusStepEvent> events = new ArrayList<>();
        ManusStepExecutor executor =
                (ManusRunContext ctx, int step) ->
                        step >= 2
                                ? ManusStepOutcome.finish(ManusTerminationReason.MODEL_DONE, "done")
                                : ManusStepOutcome.continueRun("go-" + step);
        ManusPlanner planner = ctx -> Optional.of("  先理解需求再检索  ");

        DefaultManusOrchestrator orchestrator =
                new DefaultManusOrchestrator(
                        new PlaceholderManusBrainResolver(),
                        executor,
                        events::add,
                        new FirstStepOnlyRagPolicy(),
                        planner);

        orchestrator.run(new ManusRunRequest("hello", "chat-plan", "手机", 10));

        assertThat(events).extracting(ManusStepEvent::phase).containsExactly(
                ManusStepPhase.RUN_STARTED,
                ManusStepPhase.PLAN_SNIPPET,
                ManusStepPhase.STEP_STARTED,
                ManusStepPhase.STEP_OUTCOME,
                ManusStepPhase.STEP_STARTED,
                ManusStepPhase.STEP_OUTCOME,
                ManusStepPhase.RUN_FINISHED);
        var planEvent =
                events.stream().filter(e -> e.phase() == ManusStepPhase.PLAN_SNIPPET).findFirst();
        assertThat(planEvent).isPresent();
        assertThat(planEvent.get().summary()).isEqualTo("先理解需求再检索");
    }
}
