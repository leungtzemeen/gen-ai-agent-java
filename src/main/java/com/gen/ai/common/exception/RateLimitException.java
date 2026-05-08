package com.gen.ai.common.exception;

/**
 * WiseLink 搜索/导购链路限流熔断：Redis 计数超过会话或用户日配额时抛出。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
