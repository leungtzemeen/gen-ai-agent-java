package com.gen.ai.wiselink.security;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.gen.ai.wiselink.registry.WiseLinkToolRegistry;

/**
 * WiseLink 2.1 工具权限：按会话标识（sessionId）区分 VIP 与普通用户；对受限工具在执行前拦截，
 * 且对指定工具在非 VIP 会话中从合并后的工具 Schema 中剔除（见 {@link #isVipExclusiveSchemaToolName(String)}）。
 * <p>
 * 会话维度通过 {@link org.springframework.ai.chat.client.ChatClient} 的 {@code toolContext} 传入，
 * 键名使用 {@link #TOOL_CONTEXT_SESSION_ID_KEY}；模型调用工具时由框架传入 {@link ToolContext}。
 */
@Component
public class WiseLinkToolSecurityInterceptor {

    /** 放入 {@code ChatClient.prompt()...toolContext(Map)}，供受限工具执行时读取当前会话 ID。 */
    public static final String TOOL_CONTEXT_SESSION_ID_KEY = "wiseLinkSessionId";

    private final WiseLinkToolRegistry wiseLinkToolRegistry;

    public WiseLinkToolSecurityInterceptor(WiseLinkToolRegistry wiseLinkToolRegistry) {
        this.wiseLinkToolRegistry = wiseLinkToolRegistry;
    }

    public static final String VIP_DENIED_MESSAGE = "[权限提醒] 该功能为 VIP 专属，普通用户暂无法使用。";

    /**
     * 为 {@link com.gen.ai.wiselink.annotation.WiseLinkTool#vipOnly()} 为 true 的工具包一层执行前校验。
     */
    public List<ToolCallback> wrapCallbacks(List<ToolCallback> callbacks) {
        return callbacks.stream().map(this::wrapOne).collect(Collectors.toUnmodifiableList());
    }

    private ToolCallback wrapOne(ToolCallback delegate) {
        String name = delegate.getToolDefinition().name();
        if (wiseLinkToolRegistry.isVipOnlyTool(name)) {
            return new VipRestrictedToolCallback(delegate);
        }
        return delegate;
    }

    /**
     * sessionId 中包含子串 {@code VIP}（忽略大小写）则视为 VIP 会话。
     */
    public static boolean isVipSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return sessionId.toUpperCase(Locale.ROOT).contains("VIP");
    }

    /**
     * 仅 VIP 会话应出现在下发给模型的工具列表中的名称（与 {@link #isVipSessionId(String)} 配套）。
     * <p>
     * {@code exportShoppingReport} 及其 MCP 带前缀全名使用 {@code contains} 匹配，与导购侧审计逻辑一致。
     */
    public static boolean isVipExclusiveSchemaToolName(String toolDefinitionName) {
        if (toolDefinitionName == null) {
            return false;
        }
        if ("searchProductOnWeb".equals(toolDefinitionName)) {
            return true;
        }
        return toolDefinitionName.contains("exportShoppingReport");
    }

    static String extractSessionId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object raw = toolContext.getContext().get(TOOL_CONTEXT_SESSION_ID_KEY);
        if (raw == null) {
            return null;
        }
        String s = Objects.toString(raw, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static final class VipRestrictedToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        VipRestrictedToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
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
            String sessionId = extractSessionId(toolContext);
            if (!isVipSessionId(sessionId)) {
                return VIP_DENIED_MESSAGE;
            }
            return delegate.call(toolInput, toolContext);
        }

        @Override
        public String toString() {
            return "VipRestrictedToolCallback{" + delegate + "}";
        }
    }
}
