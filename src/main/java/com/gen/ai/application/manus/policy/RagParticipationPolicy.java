package com.gen.ai.application.manus.policy;

/**
 * 决定第 k 步是否参与 RAG（检索增强）。Phase 3 由 {@link com.gen.ai.application.manus.runtime.SpringAiManusStepExecutor}
 *（尚未实现）根据返回值选择带/不带 RAG 的 {@link org.springframework.ai.chat.client.ChatClient}。
 */
@FunctionalInterface
public interface RagParticipationPolicy {

    /**
     * @param stepIndex 从 1 开始
     * @return true 表示本步应挂载 RAG Advisor
     */
    boolean useRag(int stepIndex);
}
