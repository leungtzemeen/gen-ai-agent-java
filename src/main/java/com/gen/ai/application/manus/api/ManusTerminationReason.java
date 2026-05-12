package com.gen.ai.application.manus.api;

/**
 * Manus 单次任务结束原因（整段编排退出时写入 {@link ManusRunResult}）。
 * <p>
 * Phase 3 起会与真实工具（如 Terminate）及模型自然结束语义对齐；Phase 1 仅用于编排层单测与占位。
 */
public enum ManusTerminationReason {

    /** 模型 / 执行器声明本步之后无需再跑外层步（含：本步最终回复未再请求工具、或已达 maxSteps 等）。 */
    MODEL_DONE,

    /** 达到 {@link ManusRunRequest#maxSteps()} 上限仍未声明结束。 */
    MAX_STEPS,

    /** 执行器或下游抛出未捕获异常（Phase 3 在编排层 try/catch 后映射）。 */
    ERROR,

    /** 预留：用户或工具链显式终止（与业务 Terminate 工具对齐时使用）。 */
    USER_OR_TOOL_TERMINATE
}
