package com.gen.ai.application.manus.runtime;

import com.gen.ai.application.manus.api.ManusChatRuntime;

/**
 * Phase 1～2 占位：无真实 {@link org.springframework.ai.chat.client.ChatClient}，仅用于打通编排与单测。
 * Phase 2 起由 {@link com.gen.ai.application.manus.runtime.DefaultManusBrainResolver} 替换为携带真实 client 的实现。
 */
public final class PlaceholderManusChatRuntime implements ManusChatRuntime {

    private final String debugId;

    public PlaceholderManusChatRuntime(String debugId) {
        this.debugId = debugId;
    }

    @Override
    public String runtimeDebugId() {
        return debugId;
    }
}
