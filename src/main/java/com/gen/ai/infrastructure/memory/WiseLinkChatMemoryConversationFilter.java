package com.gen.ai.infrastructure.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 记忆瘦身：持久化前剔除历史 WISELINK 注入块（如旧版 roundtrip 标记段）；保留 {@link ToolResponseMessage} 不动。
 */
@Component
public class WiseLinkChatMemoryConversationFilter implements ChatMemoryConversationCleaner {

    private static final Pattern WISELINK_ROUNDTRIP_REGION =
            Pattern.compile(
                    "<<<WISELINK_ROUNDTRIP[^>]*>>>[\\s\\S]*?<<<WISELINK_ROUNDTRIP_END>>>",
                    Pattern.MULTILINE);

    @Override
    public List<Message> clean(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            Message one = sanitizeOne(m);
            if (one != null) {
                out.add(one);
            }
        }
        return out;
    }

    private static Message sanitizeOne(Message message) {
        if (message instanceof ToolResponseMessage) {
            return message;
        }
        if (message instanceof UserMessage um) {
            String t = stripInjectionNoise(um.getText());
            if (t == null || t.isBlank()) {
                return null;
            }
            return new UserMessage(t.strip());
        }
        if (message instanceof AssistantMessage am) {
            String raw = am.getText();
            String t = stripInjectionNoise(raw);
            if (am.hasToolCalls()) {
                return AssistantMessage.builder().content(t != null ? t : "").toolCalls(am.getToolCalls()).build();
            }
            if (t == null || t.isBlank()) {
                return null;
            }
            return new AssistantMessage(t.strip());
        }
        if (message instanceof SystemMessage sm) {
            String t = stripInjectionNoise(sm.getText());
            if (t == null || t.isBlank()) {
                return null;
            }
            return new SystemMessage(t.strip());
        }
        return message;
    }

    static String stripInjectionNoise(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        s = WISELINK_ROUNDTRIP_REGION.matcher(s).replaceAll("");
        s = s.replaceAll("(?m)^\\s*$\\R?", "");
        return s.strip();
    }
}
