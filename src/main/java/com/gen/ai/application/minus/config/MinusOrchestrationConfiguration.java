package com.gen.ai.application.minus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusStepEventSink;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator;
import com.gen.ai.application.minus.orchestration.MinusOrchestrator;
import com.gen.ai.application.minus.policy.RagParticipationPolicy;
import com.gen.ai.application.minus.runtime.LoggingMinusStepEventSink;
import com.gen.ai.application.minus.runtime.NoOpMinusStepEventSink;

/**
 * Minus 编排 Bean：Phase 4 起可将 {@link MinusStepEventSink} 换为 SSE 实现；当前默认日志 + 吞事件。
 */
@Configuration
public class MinusOrchestrationConfiguration {

    @Bean
    MinusStepEventSink minusStepEventSink() {
        return new LoggingMinusStepEventSink(NoOpMinusStepEventSink.INSTANCE);
    }

    @Bean
    MinusOrchestrator minusOrchestrator(
            MinusBrainResolver minusBrainResolver,
            MinusStepExecutor minusStepExecutor,
            MinusStepEventSink minusStepEventSink,
            RagParticipationPolicy ragParticipationPolicy) {
        return new DefaultMinusOrchestrator(
                minusBrainResolver, minusStepExecutor, minusStepEventSink, ragParticipationPolicy);
    }
}
