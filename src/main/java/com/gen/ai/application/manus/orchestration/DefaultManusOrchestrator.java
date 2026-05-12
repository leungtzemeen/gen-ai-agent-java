package com.gen.ai.application.manus.orchestration;

import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusRunContext;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusRunResult;
import com.gen.ai.application.manus.api.ManusStepEvent;
import com.gen.ai.application.manus.api.ManusStepEventSink;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.api.ManusStepOutcome;
import com.gen.ai.application.manus.api.ManusTerminationReason;
import com.gen.ai.application.manus.policy.RagParticipationPolicy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public final class DefaultManusOrchestrator implements ManusOrchestrator {

    private final ManusBrainResolver brainResolver;
    private final ManusStepExecutor stepExecutor;
    private final ManusStepEventSink stepEventSink;
    private final RagParticipationPolicy ragParticipationPolicy;

    @Override
    public ManusRunResult run(ManusRunRequest request) {
        log.info(
                ">>>> [Manus-Orchestrator] 开始任务 chatId={} maxSteps={} category={}",
                request.chatId(),
                request.maxSteps(),
                request.category() == null ? "<none>" : request.category());

        AtomicInteger manusTaskToolBudget = new AtomicInteger(0);
        ManusRunContext context = new ManusRunContext(request, brainResolver.resolve(request), manusTaskToolBudget);
        log.info(
                ">>>> [Manus-Orchestrator] 已创建整任务工具预算计数器 identityHashCode={}（供 PerRequestToolBudget 全步共享）",
                System.identityHashCode(manusTaskToolBudget));
        log.info(
                ">>>> [Manus-Orchestrator] 已冻结运行时 runtimeDebugId={}（本方法内不再调用 resolve）",
                context.chatRuntime().runtimeDebugId());

        stepEventSink.onEvent(
                ManusStepEvent.runStarted("Manus run started for chatId=" + request.chatId()));

        int executed = 0;
        String lastSummary = "";

        for (int step = 1; step <= request.maxSteps(); step++) {
            boolean ragOn = ragParticipationPolicy.useRag(step);
            log.info(
                    ">>>> [Manus-Orchestrator] 外层 step={}/{} ragOn={} runtimeId={}",
                    step,
                    request.maxSteps(),
                    ragOn,
                    context.chatRuntime().runtimeDebugId());

            stepEventSink.onEvent(
                    ManusStepEvent.stepStarted(step, "Step " + step + " starting (rag=" + ragOn + ")"));

            ManusStepOutcome outcome = stepExecutor.execute(context, step);
            executed = step;
            lastSummary = outcome.stepSummaryForUi();

            stepEventSink.onEvent(
                    ManusStepEvent.stepOutcome(
                            step,
                            outcome.stepSummaryForUi(),
                            Optional.empty()));

            if (outcome.finishedRun()) {
                ManusTerminationReason reason =
                        outcome.terminationReason().orElse(ManusTerminationReason.MODEL_DONE);
                log.info(
                        ">>>> [Manus-Orchestrator] 任务结束 step={} reason={} summary={}",
                        step,
                        reason,
                        lastSummary);
                stepEventSink.onEvent(ManusStepEvent.runFinished("run finished after step " + step, reason));
                return new ManusRunResult(lastSummary, reason, executed);
            }
        }

        log.warn(
                ">>>> [Manus-Orchestrator] 达到 maxSteps={} 仍未声明结束，返回 MAX_STEPS。runtimeId={}",
                request.maxSteps(),
                context.chatRuntime().runtimeDebugId());
        stepEventSink.onEvent(
                ManusStepEvent.runFinished("reached max steps " + request.maxSteps(), ManusTerminationReason.MAX_STEPS));
        return new ManusRunResult(lastSummary, ManusTerminationReason.MAX_STEPS, executed);
    }
}
