package com.gen.ai.application.manus.runtime;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import com.gen.ai.application.manus.api.ManusPlanner;
import com.gen.ai.application.manus.api.ManusRunContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 使用与导购 Manus 相同的 {@link ChatModel} Bean 做一次<strong>无 Memory、无工具、无 RAG</strong>的短调用，
 * 产出仅供 SSE {@link com.gen.ai.application.manus.api.ManusStepPhase#PLAN_SNIPPET} 展示；失败时静默跳过。
 */
@Slf4j
@RequiredArgsConstructor
public final class LlmManusPlanner implements ManusPlanner {

    private static final String SYSTEM =
            "你是导购智能体的规划助手。根据用户一句需求，用中文写 2～4 句简短计划：准备如何帮助用户"
                    + "（可提及是否需要查知识库或调用工具，但不要编造已执行的结果）。"
                    + "不要输出 Markdown 标题或列表符号以外的装饰；不要调用任何函数。";

    private final ChatModel chatModel;

    @Override
    public Optional<String> planBrief(ManusRunContext context) {
        String user = context.request().userMessage();
        if (user == null || user.isBlank()) {
            return Optional.empty();
        }
        try {
            ChatClient ephemeral = ChatClient.builder(chatModel).build();
            String text =
                    ephemeral.prompt()
                            .system(SYSTEM)
                            .user("用户需求：\n" + user.strip())
                            .call()
                            .content();
            if (text == null) {
                return Optional.empty();
            }
            String stripped = text.strip();
            return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
        } catch (Exception e) {
            log.warn(">>>> [Manus-Planner] traceId={} planBrief 调用失败，跳过计划事件: {}", context.traceId(), e.toString());
            return Optional.empty();
        }
    }
}
