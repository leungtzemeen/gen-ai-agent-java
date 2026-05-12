package com.gen.ai.application.manus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.gen.ai.application.manus.api.ManusRunContext;
import com.gen.ai.application.manus.api.ManusRunRequest;
import com.gen.ai.application.manus.api.ManusStepEventSink;
import com.gen.ai.application.manus.api.ManusStepExecutor;
import com.gen.ai.application.manus.api.ManusStepOutcome;
import com.gen.ai.application.manus.api.ManusTerminationReason;
import com.gen.ai.application.manus.orchestration.DefaultManusOrchestrator;
import com.gen.ai.application.manus.policy.FirstStepOnlyRagPolicy;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Phase 2/3 验收：Spring 上下文中 {@link DefaultManusBrainResolver} 解析出的带 RAG {@link ChatClient} 非空；
 * Phase 3：同一次 Manus 多步内第 1 步与第 2 步择不同 client（RAG / 无 RAG），工具预算计数器同一实例。
 */
@SpringBootTest(properties = "spring.ai.mcp.client.enabled=false")
@TestPropertySource(
        properties = {
            "spring.ai.dashscope.api-key=dummy-key-for-manus-brain-resolver-spring-test"
        })
class DefaultManusBrainResolverSpringBootTest {

    @Autowired
    private DefaultManusBrainResolver defaultManusBrainResolver;

    @Test
    void resolve_exposesNonEmptyFrozenChatClient() {
        ManusRunRequest request = new ManusRunRequest("hello", "spring-test-session", "手机", 5);
        var rt = defaultManusBrainResolver.resolve(request);

        assertThat(rt.frozenChatClient()).isPresent();
        assertThat(rt.runtimeDebugId()).contains("spring-test-session");
    }

    @Test
    void orchestrator_twoSteps_ragOnlyFirstStep_differentFrozenClients_sameToolBudget() {
        List<ChatClient> captured = new ArrayList<>();
        List<AtomicInteger> budgets = new ArrayList<>();
        FirstStepOnlyRagPolicy policy = new FirstStepOnlyRagPolicy();
        ManusStepExecutor executor =
                (ManusRunContext ctx, int step) -> {
                    var runtime = (ChatClientManusChatRuntime) ctx.chatRuntime();
                    captured.add(runtime.selectForStep(step, policy));
                    budgets.add(ctx.manusTaskToolBudget());
                    return step >= 2
                            ? ManusStepOutcome.finish(ManusTerminationReason.MODEL_DONE, "done")
                            : ManusStepOutcome.continueRun("go-" + step);
                };
        ManusStepEventSink sink = e -> {};

        DefaultManusOrchestrator orchestrator =
                new DefaultManusOrchestrator(defaultManusBrainResolver, executor, sink, policy);

        orchestrator.run(new ManusRunRequest("q", "same-client-chat", null, 10));

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).isNotSameAs(captured.get(1));
        assertThat(budgets.get(0)).isSameAs(budgets.get(1));
    }
}
