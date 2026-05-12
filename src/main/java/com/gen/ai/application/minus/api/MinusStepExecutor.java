package com.gen.ai.application.minus.api;

/**
 * 执行第 k 步「对话动作」（Phase 3 内为一次或多次 Spring AI 调用链）；不负责 maxSteps 与 Step 写 Memory。
 */
@FunctionalInterface
public interface MinusStepExecutor {

    /**
     * @param context   含冻结 {@link MinusChatRuntime}，各步必须同一引用
     * @param stepIndex 从 1 开始
     * @return 是否结束整次 Minus、以及本步 UI 摘要
     */
    MinusStepOutcome execute(MinusRunContext context, int stepIndex);
}
