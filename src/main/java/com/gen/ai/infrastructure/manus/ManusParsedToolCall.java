package com.gen.ai.infrastructure.manus;

import java.util.Objects;

/**
 * Parsed tool invocation from model text (JSON-first, optionally fuzzy).
 */
public record ManusParsedToolCall(String toolName, String argumentsJson) {

    public ManusParsedToolCall {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
