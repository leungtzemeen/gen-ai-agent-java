package com.gen.ai.application.manus.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贯穿 Manus 多步的只读上下文：一次 {@link #request()} 对应一次 {@link #chatRuntime()} 冻结结果；
 * {@link #manusTaskToolBudget()} 为<strong>整次 Manus 任务</strong>共享，传入
 * {@link com.gen.ai.infrastructure.agent.toolcallback.PerRequestToolBudgetToolCallback} 装饰链。
 */
public record ManusRunContext(ManusRunRequest request, ManusChatRuntime chatRuntime, AtomicInteger manusTaskToolBudget) {

    public ManusRunContext {
        Objects.requireNonNull(manusTaskToolBudget, "manusTaskToolBudget");
    }
}
