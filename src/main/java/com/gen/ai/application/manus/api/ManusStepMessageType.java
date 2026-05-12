package com.gen.ai.application.manus.api;

/**
 * SSE / 可观测载荷类别：便于前端把「编排元信息」「模型正文」「仍请求工具」区分开，不改变编排语义。
 */
public enum ManusStepMessageType {

    /** 编排层生命周期（RUN_STARTED、STEP_STARTED、RUN_FINISHED 等）。 */
    META,

    /** Phase B：首步前计划摘要（对应 {@link ManusStepPhase#PLAN_SNIPPET}）。 */
    PLAN_SNIPPET,

    /** 本步助手自然语言可见摘要（对应 {@link ManusStepPhase#STEP_OUTCOME} 的主文本）。 */
    MODEL,

    /**
     * 本步结束后模型仍声明需要工具调用（Spring AI {@code ChatResponse#hasToolCalls()}），外层将再开一步。
     */
    TOOL
}
