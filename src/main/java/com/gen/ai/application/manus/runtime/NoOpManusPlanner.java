package com.gen.ai.application.manus.runtime;

import java.util.Optional;

import com.gen.ai.application.manus.api.ManusPlanner;
import com.gen.ai.application.manus.api.ManusRunContext;

/**
 * 默认无计划：不增加首步前 LLM 调用。
 */
public enum NoOpManusPlanner implements ManusPlanner {
    INSTANCE;

    @Override
    public Optional<String> planBrief(ManusRunContext context) {
        return Optional.empty();
    }
}
