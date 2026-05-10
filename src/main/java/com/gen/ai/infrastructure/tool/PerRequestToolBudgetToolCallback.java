package com.gen.ai.infrastructure.tool;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * 本请求内共享 {@link AtomicInteger}，限制「真实 leaf 调用」总次数（含 leaf 抛异常）；超限则不再调用
 * delegate，直接返回断路提示。
 */
@Slf4j
public final class PerRequestToolBudgetToolCallback implements ToolCallback {

    /** 与截断断路文案一致，便于模型统一收尾。 */
    public static final String BUDGET_EXCEEDED_NOTICE =
            "\n\n[SYSTEM NOTICE: 本用户请求内所有工具调用额度已用尽，禁止再调用任何工具，仅根据已有 observation 结案。]";

    private final ToolCallback delegate;
    private final AtomicInteger invokedInRequest;
    private final int maxInvocations;
    private final String budgetExceededNotice;

    public PerRequestToolBudgetToolCallback(
            ToolCallback delegate, AtomicInteger invokedInRequest, int maxInvocations, String budgetExceededNotice) {
        this.delegate = delegate;
        this.invokedInRequest = invokedInRequest;
        this.maxInvocations = maxInvocations;
        this.budgetExceededNotice = budgetExceededNotice;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        if (invokedInRequest.get() >= maxInvocations) {
            log.warn(
                    ">>>> [Tool-Budget] 工具 [{}] 触发本请求工具次数上限！\n"
                            + "   ├─ 当前累计（已达上限，未再调用 delegate）: {}\n"
                            + "   ├─ 上限: {}\n"
                            + "   └─ 已返回断路提示给模型。",
                    toolName,
                    invokedInRequest.get(),
                    maxInvocations);
            return budgetExceededNotice;
        }
        try {
            return delegate.call(toolInput, toolContext);
        } finally {
            int after = invokedInRequest.incrementAndGet();
            log.debug(
                    ">>>> [Tool-Budget] 工具 [{}] leaf 调用已计入，本请求累计 {}/{}",
                    toolName,
                    after,
                    maxInvocations);
        }
    }

    @Override
    public String toString() {
        return "PerRequestToolBudgetToolCallback{" + delegate + "}";
    }
}
