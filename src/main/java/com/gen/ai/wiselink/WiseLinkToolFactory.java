package com.gen.ai.wiselink;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.gen.ai.wiselink.registry.WiseLinkToolRegistry;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

/**
 * WiseLink 工具统一出口：供 {@link ChatClient} 请求链路通过 {@link ChatClient.ChatClientRequestSpec#toolCallbacks(List)} 等方式挂载。
 */
@Component
public class WiseLinkToolFactory {

    private final WiseLinkToolRegistry registry;
    private final WiseLinkToolSecurityInterceptor toolSecurityInterceptor;

    public WiseLinkToolFactory(WiseLinkToolRegistry registry, WiseLinkToolSecurityInterceptor toolSecurityInterceptor) {
        this.registry = registry;
        this.toolSecurityInterceptor = toolSecurityInterceptor;
    }

    /** 全部已注册工具（含 VIP 受限工具包装），顺序与启动扫描顺序一致。 */
    public List<ToolCallback> toolCallbacks() {
        return toolSecurityInterceptor.wrapCallbacks(registry.getAllCallbacksAsList());
    }

    public ToolCallback[] toolCallbacksArray() {
        List<ToolCallback> list = toolCallbacks();
        return list.toArray(ToolCallback[]::new);
    }
}
