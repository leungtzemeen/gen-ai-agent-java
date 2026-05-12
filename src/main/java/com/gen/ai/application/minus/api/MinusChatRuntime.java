package com.gen.ai.application.minus.api;

/**
 * 「本次 Minus 任务」冻结使用的对话引擎抽象；Phase 2 起由 {@link MinusBrainResolver} 解析并持有真实
 * {@link org.springframework.ai.chat.client.ChatClient}。
 * <p>
 * 编排层对一次 {@link MinusRunRequest} 只应解析<strong>一次</strong>，整段循环内引用不变，避免步间误换
 * {@code @Primary} 模型（历史事故）。
 */
public interface MinusChatRuntime {

    /** 用于日志关联：占位实现可返回固定串；真实实现可返回 hash 或配置 tag。 */
    String runtimeDebugId();
}
