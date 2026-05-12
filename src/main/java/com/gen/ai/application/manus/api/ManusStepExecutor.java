package com.gen.ai.application.manus.api;

/**
 * 执行第 k 步「对话动作」（Phase 3 内为一次或多次 Spring AI 调用链）；不负责 maxSteps 与 Step 写 Memory。
 */
@FunctionalInterface
public interface ManusStepExecutor {

    /**
     * @param context   含冻结 {@link ManusChatRuntime}，各步必须同一引用
     * @param stepIndex 从 1 开始
     * @return 是否结束整次 Manus、以及本步 UI 摘要
     */
    ManusStepOutcome execute(ManusRunContext context, int stepIndex);
}
