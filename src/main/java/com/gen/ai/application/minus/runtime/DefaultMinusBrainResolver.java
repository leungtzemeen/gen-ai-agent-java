package com.gen.ai.application.minus.runtime;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gen.ai.application.minus.api.MinusBrainResolver;
import com.gen.ai.application.minus.api.MinusChatRuntime;
import com.gen.ai.application.minus.api.MinusRunRequest;
import com.gen.ai.application.shopping.ShoppingGuideChatClientFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 2：从导购共用工厂 {@link ShoppingGuideChatClientFactory} 构建<strong>一次</strong>
 * {@link ChatClient} 并封装为 {@link ChatClientMinusChatRuntime}。
 * <p>
 * 日后大模型路由：可新增另一实现类（或替换本类内部策略），仍须保证「单次 {@link MinusRunRequest} 只 resolve
 * 一次、循环内同一 {@link ChatClient}」；{@link com.gen.ai.application.minus.orchestration.DefaultMinusOrchestrator}
 * 已强制单次 resolve。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultMinusBrainResolver implements MinusBrainResolver {

    private final ShoppingGuideChatClientFactory shoppingGuideChatClientFactory;

    @Value("${wiselink.active-brain:unknown}")
    private String activeBrainTag;

    @Override
    public MinusChatRuntime resolve(MinusRunRequest request) {
        String label = "minus:chatId=" + request.chatId() + ":brain=" + activeBrainTag;
        ChatClient withRag = shoppingGuideChatClientFactory.buildFrozenClient(label + "[rag]");
        ChatClient withoutRag = shoppingGuideChatClientFactory.buildFrozenClientWithoutRag(label + "[norag]");
        String debugId =
                label
                        + ":withRag@"
                        + Integer.toHexString(System.identityHashCode(withRag))
                        + ":noRag@"
                        + Integer.toHexString(System.identityHashCode(withoutRag))
                        + ":modelTag="
                        + activeBrainTag;
        log.info(
                ">>>> [Minus-Brain] resolve 一次冻结双 ChatClient chatId={} activeBrain={} withRagHash={} noRagHash={}",
                request.chatId(),
                activeBrainTag,
                System.identityHashCode(withRag),
                System.identityHashCode(withoutRag));
        return new ChatClientMinusChatRuntime(withRag, withoutRag, debugId);
    }
}
