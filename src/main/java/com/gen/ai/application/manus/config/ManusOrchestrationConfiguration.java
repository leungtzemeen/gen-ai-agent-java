package com.gen.ai.application.manus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gen.ai.application.manus.api.ManusBrainResolver;
import com.gen.ai.application.manus.api.ManusStepEventSink;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator;
import com.gen.ai.application.manus.orchestration.ManusOrchestrator;
import com.gen.ai.application.manus.policy.RagParticipationPolicy;
import com.gen.ai.application.manus.runtime.LoggingManusStepEventSink;
import com.gen.ai.application.manus.runtime.NoOpManusStepEventSink;

/**
 * Manus 编排 Bean：默认 {@link ManusStepEventSink} 为日志 + 吞事件；HTTP Manus 见
 * {@link com.gen.ai.application.manus.runtime.ManusChatSseService}（每请求自建 Sink，不经此 Bean）。
 */
@Configuration
public class ManusOrchestrationConfiguration {

    @Bean
    ManusStepEventSink manusStepEventSink() {
        return new LoggingManusStepEventSink(NoOpManusStepEventSink.INSTANCE);
    }

    @Bean
    ManusOrchestrator manusOrchestrator(
            ManusBrainResolver manusBrainResolver,
            ManusStepExecutor manusStepExecutor,
            ManusStepEventSink manusStepEventSink,
            RagParticipationPolicy ragParticipationPolicy) {
        return new DefaultManusOrchestrator(
                manusBrainResolver, manusStepExecutor, manusStepEventSink, ragParticipationPolicy);
    }
}
