package com.gen.ai.application.manus.orchestration;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusPlanner;
import com.gen.ai.application.manus.api.ManusRunContext;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusRunResult;
import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.api.ManusStepMessageType;
import com.gen.ai.application.manus.api.ManusStepOutcome;
import com.gen.ai.application.manus.api.ManusTerminationReason;
import com.gen.ai.application.manus.policy.RagParticipationPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manus 外层编排：仅负责「单次 resolve 冻结运行时 → 多步循环 → 事件外发 → 终止判断」。
 * <ul>
 *   <li>不写 HTTP、不写敏感词、不直接碰 ChatMemory。</li>
 *   <li>与 {@link com.gen.ai.infrastructure.agent.toolcallback.PerRequestToolBudgetToolCallback} 的配合：
 *       Phase 3～5 在构建本任务所用 {@link org.springframework.ai.chat.client.ChatClient} 时，应为<strong>整次 Manus
 *       任务</strong>创建<strong>一个</strong>{@link java.util.concurrent.atomic.AtomicInteger} 传入工具装饰链，
 *       避免「每外层一步重置预算」导致工具滥用或计数失真。</li>
 *   <li>Phase B：可选 {@link ManusPlanner} 在首步前产出 {@link com.gen.ai.application.manus.api.ManusStepPhase#PLAN_SNIPPET}，
 *       不得写入 ChatMemory。</li>
 *   <li>Phase C：单次 {@code run} 生成 {@link ManusRunContext#traceId()}，经 {@link ManusStepEvent#withRunTelemetry} 写入各
 *       SSE 事件；{@code activeBrainTag} 来自 {@link com.gen.ai.application.manus.api.ManusChatRuntime#activeBrainTag()}。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public final class DefaultManusOrchestrator implements ManusOrchestrator {

    private final ManusBrainResolver brainResolver;
    private final ManusStepExecutor stepExecutor;
    private final ManusStepEventSink stepEventSink;
    private final RagParticipationPolicy ragParticipationPolicy;
    private final ManusPlanner manusPlanner;

    @Override
    public ManusRunResult run(ManusRunRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.info(
                ">>>> [Manus-Orchestrator] 开始任务 traceId={} chatId={} maxSteps={} category={}",
                traceId,
                request.chatId(),
                request.maxSteps(),
                request.category() == null ? "<none>" : request.category());

        AtomicInteger manusTaskToolBudget = new AtomicInteger(0);
        ManusRunContext context =
                new ManusRunContext(request, brainResolver.resolve(request), manusTaskToolBudget, traceId);
        log.info(
                ">>>> [Manus-Orchestrator] traceId={} 已创建整任务工具预算计数器 identityHashCode={}（供 PerRequestToolBudget 全步共享）",
                traceId,
                System.identityHashCode(manusTaskToolBudget));
        log.info(
                ">>>> [Manus-Orchestrator] traceId={} 已冻结运行时 runtimeDebugId={}（本方法内不再调用 resolve）",
                traceId,
                context.chatRuntime().runtimeDebugId());

        emit(
                context,
                ManusStepEvent.runStarted("Manus run started for chatId=" + request.chatId()));

        manusPlanner
                .planBrief(context)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .ifPresent(text -> emit(context, ManusStepEvent.planSnippet(text)));

        int executed = 0;
        String lastSummary = "";

        for (int step = 1; step <= request.maxSteps(); step++) {
            boolean ragOn = ragParticipationPolicy.useRag(step);
            log.info(
                    ">>>> [Manus-Orchestrator] traceId={} 外层 step={}/{} ragOn={} runtimeId={}",
                    traceId,
                    step,
                    request.maxSteps(),
                    ragOn,
                    context.chatRuntime().runtimeDebugId());

            emit(
                    context,
                    ManusStepEvent.stepStarted(step, "Step " + step + " starting (rag=" + ragOn + ")", ragOn));

            ManusStepOutcome outcome = stepExecutor.execute(context, step);
            executed = step;
            lastSummary = outcome.stepSummaryForUi();

            boolean pendingTools = outcome.hasPendingToolCalls().orElse(false);
            ManusStepMessageType payloadKind =
                    pendingTools ? ManusStepMessageType.TOOL : ManusStepMessageType.MODEL;
            emit(
                    context,
                    ManusStepEvent.stepOutcome(
                            step,
                            outcome.stepSummaryForUi(),
                            outcome.toolHintForObservers(),
                            ragOn,
                            outcome.stepLatencyMillis(),
                            payloadKind,
                            outcome.hasPendingToolCalls(),
                            outcome.stepSummaryShort()));

            if (outcome.finishedRun()) {
                ManusTerminationReason reason =
                        outcome.terminationReason().orElse(ManusTerminationReason.MODEL_DONE);
                log.info(
                        ">>>> [Manus-Orchestrator] traceId={} 任务结束 step={} reason={} summary={}",
                        traceId,
                        step,
                        reason,
                        lastSummary);
                emit(context, ManusStepEvent.runFinished("run finished after step " + step, reason));
                return new ManusRunResult(lastSummary, reason, executed);
            }
        }

        log.warn(
                ">>>> [Manus-Orchestrator] traceId={} 达到 maxSteps={} 仍未声明结束，返回 MAX_STEPS。runtimeId={}",
                traceId,
                request.maxSteps(),
                context.chatRuntime().runtimeDebugId());
        emit(
                context,
                ManusStepEvent.runFinished("reached max steps " + request.maxSteps(), ManusTerminationReason.MAX_STEPS));
        return new ManusRunResult(lastSummary, ManusTerminationReason.MAX_STEPS, executed);
    }

    private void emit(ManusRunContext ctx, ManusStepEvent event) {
        stepEventSink.onEvent(event.withRunTelemetry(ctx.traceId(), ctx.chatRuntime().activeBrainTag()));
    }
}
