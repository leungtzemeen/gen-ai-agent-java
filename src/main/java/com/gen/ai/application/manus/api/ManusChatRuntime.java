package com.gen.ai.application.manus.api;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 「本次 Manus 任务」冻结使用的对话引擎抽象；Phase 2 起由 {@link ManusBrainResolver} 解析并持有真实
 * {@link ChatClient}（见 {@link #frozenChatClient()}）。
 * <p>
 * 编排层对一次 {@link ManusRunRequest} 只应解析<strong>一次</strong>，整段循环内引用不变，避免步间误换
 * {@code @Primary} 模型（历史事故）。
 */
public interface ManusChatRuntime {

    /** 用于日志关联：占位实现可返回固定串；真实实现可返回 chatId + client 身份摘要。 */
    String runtimeDebugId();

    /**
     * Phase 2+：本任务冻结的 {@link ChatClient}，整次 Manus 多步内须为<strong>同一实例</strong>。
     * <p>
     * Phase 1 占位实现依赖默认空返回。
     */
    default Optional<ChatClient> frozenChatClient() {
        return Optional.empty();
    }

    /**
     * Phase C：当前任务解析时绑定的「大脑」标签（如 {@code wiselink.active-brain}），供日志与 SSE 关联；占位实现默认
     * empty，路由扩展时由 {@link ManusBrainResolver} 实现类写入。
     */
    default Optional<String> activeBrainTag() {
        return Optional.empty();
    }
}
