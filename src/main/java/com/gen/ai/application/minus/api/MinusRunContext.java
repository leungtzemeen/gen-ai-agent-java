package com.gen.ai.application.minus.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贯穿 Minus 多步的只读上下文：一次 {@link #request()} 对应一次 {@link #chatRuntime()} 冻结结果；
 * {@link #minusTaskToolBudget()} 为<strong>整次 Minus 任务</strong>共享，传入
 * {@link com.gen.ai.infrastructure.agent.toolcallback.PerRequestToolBudgetToolCallback} 装饰链。
 */
public record MinusRunContext(MinusRunRequest request, MinusChatRuntime chatRuntime, AtomicInteger minusTaskToolBudget) {

    public MinusRunContext {
        Objects.requireNonNull(minusTaskToolBudget, "minusTaskToolBudget");
    }
}
