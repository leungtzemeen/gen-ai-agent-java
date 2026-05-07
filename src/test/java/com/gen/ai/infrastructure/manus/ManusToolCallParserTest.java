package com.gen.ai.infrastructure.manus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ManusToolCallParserTest {

    @Test
    void findsToolJsonAfterLongPlanAndProseUnderActionLabel() {
        String raw =
                """
                ### Plan
                1. Query stock
                2. Reply user
                Lorem ipsum dolor sit amet. Repeated explanation lines here.
                ### Action:
                Optional prose before brace is allowed by relaxed scanning.
                {"action":"getProductStockFunction","action_input":{"sku":"x"}}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("getProductStockFunction");
    }

    @Test
    void prefersLastActionAnchoredJsonWhenMultipleLabelsExist() {
        String raw =
                """
                ### Action:
                preamble then invalid brace below { not closed
                ### Action:
                {"action":"secondTool","action_input":{}}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("secondTool");
    }

    @Test
    void parsesJsonBelowTripleHashActionWithoutBackticks() {
        String raw =
                """
                Thought: call stock
                ### Action:
                {"action":"getProductStockFunction","action_input":{"sku":"abc"}}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("getProductStockFunction");
        assertThat(tc.get().argumentsJson()).contains("sku");
    }

    @Test
    void braceBacktrackFindsJsonWhenActionLabelAboveWithWhitespaceOnlyGap() {
        String raw =
                """
                Thought: x
                Action:

                {"tool":"myTool","arguments":{"q":"1"}}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("myTool");
    }

    @Test
    void stripsJsonFenceThenParsesAction() {
        String raw =
                """
                Thought: need search
                Action:
                ```json
                {"action":"exportShoppingReport","action_input":{"title":"x"}}
                ```
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("exportShoppingReport");
        assertThat(tc.get().argumentsJson()).contains("title");
    }

    @Test
    void fuzzyActionLinesWhenJsonInvalid() {
        String raw =
                """
                Thought: try tool
                Action: wiseLinkSearchProducts
                Action Input: {"query":"音箱"}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("wiseLinkSearchProducts");
        assertThat(tc.get().argumentsJson()).contains("query");
    }

    @Test
    void finalAnswerDetectedWhenPresent() {
        String raw = "Thought: done\nFINAL_ANSWER: 你好，推荐如下。";
        assertThat(ManusToolCallParser.tryParseFinalAnswer(raw)).contains("你好，推荐如下。");
    }

    @Test
    void toolCallParsesWhenFinalAnswerAppearsEarlierInNoise() {
        String raw =
                """
                Thought: mention FINAL_ANSWER: placeholder then tool
                ### Action:
                {"action":"getProductPriceFunction","action_input":{"id":"1"}}
                """;
        Optional<ManusParsedToolCall> tc = ManusToolCallParser.tryParseToolCall(raw);
        assertThat(tc).isPresent();
        assertThat(tc.get().toolName()).isEqualTo("getProductPriceFunction");
    }

    @Test
    void sanitizeRenamesCityNameForMapsWeather() {
        ManusParsedToolCall raw =
                new ManusParsedToolCall("maps_weather", "{\"cityName\":\"广州\"}");
        Optional<ManusParsedToolCall> s = ManusToolCallParser.sanitizeParsedToolCall(raw);
        assertThat(s).isPresent();
        assertThat(s.get().argumentsJson()).contains("\"city\"");
        assertThat(s.get().argumentsJson()).contains("广州");
        assertThat(s.get().argumentsJson()).doesNotContain("cityName");
    }

    @Test
    void sanitizeRejectsNonObjectToolInput() {
        ManusParsedToolCall raw = new ManusParsedToolCall("someTool", "[1,2]");
        assertThat(ManusToolCallParser.sanitizeParsedToolCall(raw)).isEmpty();
    }

    @Test
    void stripFencesOnly() {
        String s = ManusToolCallParser.stripMarkdownCodeFences("前置```json\n{\"a\":1}\n```后缀");
        assertThat(s).doesNotContain("```");
        assertThat(s).contains("{\"a\":1}");
    }
}
