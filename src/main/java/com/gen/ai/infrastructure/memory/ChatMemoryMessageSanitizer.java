package com.gen.ai.infrastructure.memory;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 把即将交给模型（或从文件读出的滑动窗口）的消息列表整理成 OpenAI/DeepSeek 可接受的形状。
 * <p>
 * Manus / 导购共用 {@link org.springframework.ai.chat.memory.ChatMemory} 时，历史里会出现
 * {@code assistant(tool_calls)} 与 {@link ToolResponseMessage} 成对出现；若 {@link FileChatMemoryRepository}
 * 按 {@code lastN} 取子列表，或 {@link org.springframework.ai.chat.memory.MessageWindowChatMemory} 丢弃最旧消息，
 * 可能在窗口<strong>左缘</strong>留下孤立的 {@code tool}，或在中间出现「含 tool_calls 的 assistant 后紧跟 user」，
 * 从而触发 HTTP 400。本类在<strong>读盘返回前</strong>与<strong>写盘保存前</strong>各做一次轻量修复。
 */
public final class ChatMemoryMessageSanitizer {

    private ChatMemoryMessageSanitizer() {}

    /**
     * 就地修改 {@code messages}，保证不出现明显违反 Chat Completions 规则的头部/中部/尾部片段。
     *
     * @return 是否做过删除（便于调用方打诊断日志）
     */
    public static boolean sanitize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int before = messages.size();
        stripLeadingOrphanTools(messages);
        removeAssistantsWithToolCallsNotImmediatelyFollowedByTool(messages);
        stripTrailingAssistantWithToolCalls(messages);
        return messages.size() != before;
    }

    /** 列表首条不能是 role=tool：前面没有带 tool_calls 的 assistant。 */
    private static void stripLeadingOrphanTools(List<Message> messages) {
        while (!messages.isEmpty() && messages.get(0) instanceof ToolResponseMessage) {
            messages.remove(0);
        }
    }

    /**
     * 含 {@code tool_calls} 的 assistant 在 API 视角下必须紧跟 tool 消息；若紧挨的是 user/system 等，
     * 说明窗口把 tool 截到左边去了，只能删掉这条半截 assistant。
     */
    private static void removeAssistantsWithToolCallsNotImmediatelyFollowedByTool(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof AssistantMessage am) || !am.hasToolCalls()) {
                continue;
            }
            boolean nextIsTool =
                    i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage;
            if (!nextIsTool) {
                messages.remove(i);
                i--;
            }
        }
    }

    /** 窗口末尾不应停留在「仍声明要调工具」的 assistant 上（工具回合未在列表内闭合）。 */
    private static void stripTrailingAssistantWithToolCalls(List<Message> messages) {
        while (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last instanceof AssistantMessage am && am.hasToolCalls()) {
                messages.remove(messages.size() - 1);
            } else {
                break;
            }
        }
    }
}
