package com.gen.ai.application.minus.policy;

/**
 * 约定：仅第 1 步跑 RAG，后续步依赖工具与对话记忆推进，避免重复检索与 token 浪费。
 */
public final class FirstStepOnlyRagPolicy implements RagParticipationPolicy {

    @Override
    public boolean useRag(int stepIndex) {
        return stepIndex == 1;
    }
}
