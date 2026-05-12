package com.gen.ai.application.manus.runtime;

import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusChatRuntime;
import com.gen.ai.application.manus.api.ManusRunRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1 单元测试用占位 {@link com.gen.ai.application.manus.api.ManusBrainResolver}；Spring 生产环境请使用
 * {@link DefaultManusBrainResolver}。
 */
@Slf4j
public final class PlaceholderManusBrainResolver implements ManusBrainResolver {

    @Override
    public ManusChatRuntime resolve(ManusRunRequest request) {
        String id = "placeholder:chat=" + request.chatId();
        log.info(">>>> [Manus-Brain] resolve 一次冻结运行时 debugId={}（Phase 1 占位，无真实 ChatClient）", id);
        return new PlaceholderManusChatRuntime(id);
    }
}
