package com.gen.ai.application.manus.orchestration;

import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusRunResult;

/**
 * Manus 编排入口；实现类须遵守「单次 {@link #run(ManusRunRequest)} 内对 {@link com.gen.ai.application.manus.api.ManusBrainResolver}
 * 只 resolve 一次」的约定（由 {@link DefaultManusOrchestrator} 实现）。
 */
public interface ManusOrchestrator {

    ManusRunResult run(ManusRunRequest request);
}
