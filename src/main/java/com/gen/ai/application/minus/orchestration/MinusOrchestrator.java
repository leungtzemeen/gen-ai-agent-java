package com.gen.ai.application.minus.orchestration;

import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusRunResult;

/**
 * Minus 编排入口；实现类须遵守「单次 {@link #run(MinusRunRequest)} 内对 {@link com.gen.ai.application.minus.api.MinusBrainResolver}
 * 只 resolve 一次」的约定（由 {@link DefaultMinusOrchestrator} 实现）。
 */
public interface MinusOrchestrator {

    MinusRunResult run(MinusRunRequest request);
}
