package com.gen.ai.application.minus.api;

/**
 * 一次 Minus 任务的输入（Phase 4 起由 Controller 根据 query {@code mode=minus} 等组装）。
 *
 * @param userMessage 用户本轮自然语言需求
 * @param chatId      会话 id，与现网 {@code sessionId} 对齐
 * @param category    可选业务分区（如 biz_category），与导购 RAG 过滤对齐；可为 null
 * @param maxSteps    外层循环上限，防止死循环与 token 失控
 */
public record MinusRunRequest(String userMessage, String chatId, String category, int maxSteps) {

    public MinusRunRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1");
        }
    }
}
