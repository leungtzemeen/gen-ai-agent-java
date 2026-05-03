package com.gen.ai.wiselink;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.gen.ai.wiselink.registry.WiseLinkToolRegistry;

/**
 * WiseLink 工具统一出口：供 {@link ChatClient} 请求链路通过 {@link ChatClient.ChatClientRequestSpec#toolCallbacks(List)} 等方式挂载。
 */
@Component
public class WiseLinkToolFactory {

    private final WiseLinkToolRegistry registry;

    public WiseLinkToolFactory(WiseLinkToolRegistry registry) {
        this.registry = registry;
    }

    /** 全部已注册工具，顺序与启动扫描顺序一致。 */
    public List<ToolCallback> toolCallbacks() {
        return registry.getAllCallbacksAsList();
    }

    public ToolCallback[] toolCallbacksArray() {
        List<ToolCallback> list = toolCallbacks();
        return list.toArray(ToolCallback[]::new);
    }
}
