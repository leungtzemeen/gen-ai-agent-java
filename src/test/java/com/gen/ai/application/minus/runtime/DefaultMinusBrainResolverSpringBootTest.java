package com.gen.ai.application.minus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.gen.ai.application.minus.api.MinusRunContext;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.minus.api.MinusStepEventSink;
import com.gen.ai.application.minus.api.MinusStepExecutor;
import com.gen.ai.application.minus.api.MinusStepOutcome;
import com.gen.ai.application.minus.api.MinusTerminationReason;
import com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator;
import com.gen.ai.application.minus.policy.FirstStepOnlyRagPolicy;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Phase 2 验收：Spring 上下文中 {@link DefaultMinusBrainResolver} 解析出的 {@link ChatClient} 非空；
 * 同一次 Minus 编排多步内 {@link ChatClient} 引用一致。
 */
@SpringBootTest(properties = "spring.ai.mcp.client.enabled=false")
@TestPropertySource(
        properties = {
            "spring.ai.dashscope.api-key=dummy-key-for-minus-brain-resolver-spring-test"
        })
class DefaultMinusBrainResolverSpringBootTest {

    @Autowired
    private DefaultMinusBrainResolver defaultMinusBrainResolver;

    @Test
    void resolve_exposesNonEmptyFrozenChatClient() {
        MinusRunRequest request = new MinusRunRequest("hello", "spring-test-session", "手机", 5);
        var rt = defaultMinusBrainResolver.resolve(request);

        assertThat(rt.frozenChatClient()).isPresent();
        assertThat(rt.runtimeDebugId()).contains("spring-test-session");
    }

    @Test
    void orchestrator_twoSteps_sameChatClientInstance() {
        List<ChatClient> captured = new ArrayList<>();
        MinusStepExecutor executor =
                (MinusRunContext ctx, int step) -> {
                    ChatClient c = ctx.chatRuntime().frozenChatClient().orElseThrow();
                    captured.add(c);
                    return step >= 2
                            ? MinusStepOutcome.finish(MinusTerminationReason.MODEL_DONE, "done")
                            : MinusStepOutcome.continueRun("go-" + step);
                };
        MinusStepEventSink sink = e -> {};

        DefaultMinusOrchestrator orchestrator =
                new DefaultMinusOrchestrator(defaultMinusBrainResolver, executor, sink, new FirstStepOnlyRagPolicy());

        orchestrator.run(new MinusRunRequest("q", "same-client-chat", null, 10));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).isSameAs(captured.get(1));
    }
}
