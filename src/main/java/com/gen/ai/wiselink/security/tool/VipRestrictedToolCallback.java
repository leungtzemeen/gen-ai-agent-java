package com.gen.ai.wiselink.security.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * VIP 专属工具的执行前校验装饰；可与 {@link com.gen.ai.infrastructure.agent.toolcallback.TruncationToolCallback}
 * 通过 {@link com.gen.ai.infrastructure.agent.toolcallback.ToolCallbackComposition} 组合（截断通常在外层）。
 */
@Slf4j
public final class VipRestrictedToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    public VipRestrictedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /** 若为 VIP 装饰则取下内层工具，否则返回原实例（供外层插入预算等装饰）。 */
    public static ToolCallback unwrapVipDelegateOrSelf(ToolCallback callback) {
        return callback instanceof VipRestrictedToolCallback v ? v.delegate : callback;
    }

    /**
     * 若 {@code originalFromFactory} 为 VIP 装饰则对 {@code inner} 重新包一层 VIP，否则返回 {@code inner}。
     */
    public static ToolCallback wrapWithVipIfMatches(ToolCallback inner, ToolCallback originalFromFactory) {
        return originalFromFactory instanceof VipRestrictedToolCallback ? new VipRestrictedToolCallback(inner) : inner;
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
        String sessionId = WiseLinkToolSecurityInterceptor.extractSessionId(toolContext);
        if (!WiseLinkToolSecurityInterceptor.isVipSessionId(sessionId)) {
            log.warn(
                    ">>>> [VIP-Tool] 工具 [{}] 非 VIP 会话，拒绝执行。\n"
                            + "   ├─ sessionId: {}\n"
                            + "   └─ 已返回权限提示（未调用 delegate）。",
                    toolName,
                    sessionId == null || sessionId.isBlank() ? "<empty>" : sessionId);
            return WiseLinkToolSecurityInterceptor.VIP_DENIED_MESSAGE;
        }
        log.debug(">>>> [VIP-Tool] 工具 [{}] VIP 校验通过，sessionId={}", toolName, sessionId);
        return delegate.call(toolInput, toolContext);
    }

    @Override
    public String toString() {
        return "VipRestrictedToolCallback{" + delegate + "}";
    }
}
