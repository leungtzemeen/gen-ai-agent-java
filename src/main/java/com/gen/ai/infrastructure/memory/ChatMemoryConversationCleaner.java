package com.gen.ai.infrastructure.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * 在 {@link FileChatMemoryRepository} 写入磁盘与按会话读取后，对消息列表做净化（问-答-工具结果导向）。
 */
@FunctionalInterface
public interface ChatMemoryConversationCleaner {

    ChatMemoryConversationCleaner NOOP = (conversationId, messages) -> messages;

    /**
     * @param conversationId 会话标识
     * @param messages       当前完整会话消息快照（可替换为新的列表实例）
     * @return 净化后的列表；不得修改传入列表的原地语义由实现保证（实现应返回新列表或不可变视图）
     */
    List<Message> clean(String conversationId, List<Message> messages);
}
