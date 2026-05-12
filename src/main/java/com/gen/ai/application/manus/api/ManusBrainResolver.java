package com.gen.ai.application.manus.api;

/**
 * 为单次 Manus 任务解析并冻结「对话引擎」。日后接入大模型路由时，仅替换本接口的实现类（开闭原则），
 * {@link com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator} 的循环骨架不变。
 * <p>
 * 调用约定：{@link com.gen.ai.application.manus.orchestration.ManusOrchestrator#run(ManusRunRequest)} 入口内
 * <strong>仅调用一次</strong> {@code resolve}，结果写入 {@link ManusRunContext} 贯穿各步。
 */
@FunctionalInterface
public interface ManusBrainResolver {

    /**
     * @param request 本次任务请求
     * @return 冻结的运行时；禁止在循环内对同一 request 重复解析出不同实例（除非显式重置任务）
     */
    ManusChatRuntime resolve(ManusRunRequest request);
}
