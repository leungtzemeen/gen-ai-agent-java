package com.gen.ai.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ChatMemoryMessageSanitizerTest {

    @Test
    void stripsLeadingOrphanTool() {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(
                ToolResponseMessage.builder()
                        .responses(List.of())
                        .build());
        messages.add(new UserMessage("hi"));

        assertThat(ChatMemoryMessageSanitizer.sanitize(messages)).isTrue();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void removesAssistantWithToolCallsWhenToolWasSlicedOff() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("id1", "function", "x", "{}");
        AssistantMessage pending =
                AssistantMessage.builder().content("calling").toolCalls(List.of(call)).build();
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(pending);
        messages.add(new UserMessage("next"));

        assertThat(ChatMemoryMessageSanitizer.sanitize(messages)).isTrue();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void keepsValidAssistantToolPair() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("id1", "function", "x", "{}");
        AssistantMessage assistant =
                AssistantMessage.builder().content("calling").toolCalls(List.of(call)).build();
        ToolResponseMessage tool =
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("id1", "x", "ok")))
                        .build();
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(assistant);
        messages.add(tool);
        messages.add(new UserMessage("follow up"));

        assertThat(ChatMemoryMessageSanitizer.sanitize(messages)).isFalse();
        assertThat(messages).hasSize(3);
    }
}
