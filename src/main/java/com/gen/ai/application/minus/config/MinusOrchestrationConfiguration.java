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
 * Minus 编排 Bean：默认 {@link MinusStepEventSink} 为日志 + 吞事件；HTTP Minus 见
 * {@link com.gen.ai.application.minus.runtime.MinusChatSseService}（每请求自建 Sink，不经此 Bean）。
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
