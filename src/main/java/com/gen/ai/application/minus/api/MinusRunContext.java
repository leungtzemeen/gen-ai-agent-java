package com.gen.ai.application.minus.api;

/**
 * 贯穿 Minus 多步的只读上下文：一次 {@link #request()} 对应一次 {@link #chatRuntime()} 冻结结果。
 */
public record MinusRunContext(MinusRunRequest request, MinusChatRuntime chatRuntime) {}
