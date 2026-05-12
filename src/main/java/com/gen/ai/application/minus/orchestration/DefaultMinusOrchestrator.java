package com.gen.ai.application.minus.orchestration;

import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusRunContext;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusRunResult;
import com.gen.ai.application.minus.api.MinusStepEvent;
import com.gen.ai.application.minus.api.MinusStepEventSink;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.api.MinusStepOutcome;
import com.gen.ai.application.minus.api.MinusTerminationReason;
import com.gen.ai.application.minus.policy.RagParticipationPolicy;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minus 外层编排：仅负责「单次 resolve 冻结运行时 → 多步循环 → 事件外发 → 终止判断」。
 * <ul>
 *   <li>不写 HTTP、不写敏感词、不直接碰 ChatMemory。</li>
 *   <li>与 {@link com.gen.ai.infrastructure.agent.toolcallback.PerRequestToolBudgetToolCallback} 的配合：
 *       Phase 3～5 在构建本任务所用 {@link org.springframework.ai.chat.client.ChatClient} 时，应为<strong>整次 Minus
 *       任务</strong>创建<strong>一个</strong>{@link java.util.concurrent.atomic.AtomicInteger} 传入工具装饰链，
 *       避免「每外层一步重置预算」导致工具滥用或计数失真。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public final class DefaultMinusOrchestrator implements MinusOrchestrator {

    private final MinusBrainResolver brainResolver;
    private final MinusStepExecutor stepExecutor;
    private final MinusStepEventSink stepEventSink;
    private final RagParticipationPolicy ragParticipationPolicy;

    @Override
    public MinusRunResult run(MinusRunRequest request) {
        log.info(
                ">>>> [Minus-Orchestrator] 开始任务 chatId={} maxSteps={} category={}",
                request.chatId(),
                request.maxSteps(),
                request.category() == null ? "<none>" : request.category());

        MinusRunContext context = new MinusRunContext(request, brainResolver.resolve(request));
        log.info(
                ">>>> [Minus-Orchestrator] 已冻结运行时 runtimeDebugId={}（本方法内不再调用 resolve）",
                context.chatRuntime().runtimeDebugId());

        stepEventSink.onEvent(
                MinusStepEvent.runStarted("Minus run started for chatId=" + request.chatId()));

        int executed = 0;
        String lastSummary = "";

        for (int step = 1; step <= request.maxSteps(); step++) {
            boolean ragOn = ragParticipationPolicy.useRag(step);
            log.info(
                    ">>>> [Minus-Orchestrator] 外层 step={}/{} ragOn={} runtimeId={}",
                    step,
                    request.maxSteps(),
                    ragOn,
                    context.chatRuntime().runtimeDebugId());

            stepEventSink.onEvent(
                    MinusStepEvent.stepStarted(step, "Step " + step + " starting (rag=" + ragOn + ")"));

            MinusStepOutcome outcome = stepExecutor.execute(context, step);
            executed = step;
            lastSummary = outcome.stepSummaryForUi();

            stepEventSink.onEvent(
                    MinusStepEvent.stepOutcome(
                            step,
                            outcome.stepSummaryForUi(),
                            Optional.empty()));

            if (outcome.finishedRun()) {
                MinusTerminationReason reason =
                        outcome.terminationReason().orElse(MinusTerminationReason.MODEL_DONE);
                log.info(
                        ">>>> [Minus-Orchestrator] 任务提前结束 step={} reason={} summary={}",
                        step,
                        reason,
                        lastSummary);
                stepEventSink.onEvent(MinusStepEvent.runFinished("run finished after step " + step, reason));
                return new MinusRunResult(lastSummary, reason, executed);
            }
        }

        log.warn(
                ">>>> [Minus-Orchestrator] 达到 maxSteps={} 仍未声明结束，返回 MAX_STEPS。runtimeId={}",
                request.maxSteps(),
                context.chatRuntime().runtimeDebugId());
        stepEventSink.onEvent(
                MinusStepEvent.runFinished("reached max steps " + request.maxSteps(), MinusTerminationReason.MAX_STEPS));
        return new MinusRunResult(lastSummary, MinusTerminationReason.MAX_STEPS, executed);
    }
}
