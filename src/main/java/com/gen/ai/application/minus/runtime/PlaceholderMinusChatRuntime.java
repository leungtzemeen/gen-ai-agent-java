package com.gen.ai.application.minus.runtime;

import com.gen.ai.application.minus.api.MinusChatRuntime;

/**
 * Phase 1～2 占位：无真实 {@link org.springframework.ai.chat.client.ChatClient}，仅用于打通编排与单测。
 * Phase 2 起由 {@link com.gen.ai.application.minus.runtime.DefaultMinusBrainResolver} 替换为携带真实 client 的实现。
 */
public final class PlaceholderMinusChatRuntime implements MinusChatRuntime {

    private final String debugId;

    public PlaceholderMinusChatRuntime(String debugId) {
        this.debugId = debugId;
    }

    @Override
    public String runtimeDebugId() {
        return debugId;
    }
}
