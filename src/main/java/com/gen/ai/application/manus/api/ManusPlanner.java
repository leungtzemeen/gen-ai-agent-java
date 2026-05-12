package com.gen.ai.application.manus.api;

import java.util.Optional;

/**
 * Phase B：在首步执行前生成一段「任务理解 / 计划摘要」，仅经 {@link ManusStepEventSink} 外发，**不**写入
 * {@link org.springframework.ai.chat.memory.ChatMemory}。
 * <p>
 * 实现须容忍失败：返回 empty 时编排照常执行。
 */
@FunctionalInterface
public interface ManusPlanner {

    /**
     * @param context 已含本次冻结的 {@link ManusChatRuntime}（与后续各步同一引用）。LLM 实现应避免污染会话
     *                Memory（例如使用无 Memory 的临时 {@link org.springframework.ai.chat.client.ChatClient}，
     *                与当前激活的 {@link org.springframework.ai.chat.model.ChatModel} 对齐即可）。
     */
    Optional<String> planBrief(ManusRunContext context);
}
