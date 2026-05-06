package com.gen.ai.infrastructure.model;

import java.util.Locale;

/**
 * 导购对话可选大模型通道：百炼 Qwen（默认）、DeepSeek（OpenAI 兼容）、本地 Ollama。
 */
public enum WiseLinkLlmProfile {

    /** 阿里云 DashScope / Qwen */
    QWEN,

    /** DeepSeek Chat（spring.ai.openai.*） */
    DEEPSEEK,

    /** 本地 Ollama（spring.ai.ollama.*） */
    OLLAMA;

    public static WiseLinkLlmProfile fromRequestParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return QWEN;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(t) || "ds".equals(t)) {
            return DEEPSEEK;
        }
        if ("ollama".equals(t)) {
            return OLLAMA;
        }
        return QWEN;
    }
}
