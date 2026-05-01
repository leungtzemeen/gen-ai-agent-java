package com.gen.ai.exception;

/**
 * 本地敏感提问拦截异常：在调用大模型前直接拒绝。
 */
public class SensitivePromptException extends RuntimeException {

    public SensitivePromptException() {
        super("Sensitive prompt blocked locally");
    }
}

