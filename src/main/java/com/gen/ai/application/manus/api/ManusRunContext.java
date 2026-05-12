package com.gen.ai.application.manus.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贯穿 Manus 多步的只读上下文：一次 {@link #request()} 对应一次 {@link #chatRuntime()} 冻结结果；
 * {@link #manusTaskToolBudget()} 为<strong>整次 Manus 任务</strong>共享，传入
 * {@link com.gen.ai.infrastructure.agent.toolcallback.PerRequestToolBudgetToolCallback} 装饰链。
 * <p>
 * Phase C：{@link #traceId()} 为单次 {@code run} 的关联 ID（日志 / SSE）；「大脑」标签见
 * {@link ManusChatRuntime#activeBrainTag()}。
 */
public record ManusRunContext(
        ManusRunRequest request, ManusChatRuntime chatRuntime, AtomicInteger manusTaskToolBudget, String traceId) {

    public ManusRunContext {
        Objects.requireNonNull(manusTaskToolBudget, "manusTaskToolBudget");
        Objects.requireNonNull(traceId, "traceId");
    }
}
