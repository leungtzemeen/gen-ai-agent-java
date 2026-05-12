package com.gen.ai.application.minus.runtime;

import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusChatRuntime;
import com.gen.ai.application.minus.api.MinusRunRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1 默认 {@link MinusBrainResolver}：返回占位运行时，保证编排层已具备「单次 resolve」调用点。
 * <p>
 * Phase 2 起可替换为从 {@link org.springframework.ai.chat.client.ChatClient.Builder} 构建的真实运行时；
 * {@link com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator} 保证整次任务仅调用一次 {@link #resolve}。
 */
@Slf4j
public final class PlaceholderMinusBrainResolver implements MinusBrainResolver {

    @Override
    public MinusChatRuntime resolve(MinusRunRequest request) {
        String id = "placeholder:chat=" + request.chatId();
        log.info(">>>> [Minus-Brain] resolve 一次冻结运行时 debugId={}（Phase 1 占位，无真实 ChatClient）", id);
        return new PlaceholderMinusChatRuntime(id);
    }
}
