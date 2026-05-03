package com.gen.ai.web.advice;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gen.ai.common.exception.SensitivePromptException;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一 REST 异常处理：本地敏感词与 DashScope 云端审核失败返回友好文案；其它运行时异常返回 500。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String SENSITIVE_HINT = "DataInspectionFailed";

    private static final String FRIENDLY_REFUSAL = "哎呀，WiseLink 现在的注意力都在导购上呢，这个话题咱们暂时不聊哦，换个宝贝问问吧？";

    @ExceptionHandler(SensitivePromptException.class)
    public ResponseEntity<String> handleSensitivePromptException(SensitivePromptException ex) {
        // 统一保持温柔拒绝口径，避免把具体命中内容返回给用户
        log.warn(">>>> [Security] 检测到敏感提问，已在本地拦截");
        return ResponseEntity.ok(FRIENDLY_REFUSAL);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException ex) {
        if (isDashScopeSensitiveInspectionFailed(ex)) {
            log.warn(">>>> [WiseLink-Guard] 命中敏感词拦截（返回 200）：{}", safeMessage(ex));
            return ResponseEntity.ok(FRIENDLY_REFUSAL);
        }

        log.error(">>>> [WiseLink-Guard] 未处理的运行时异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal Server Error");
    }

    private static boolean isDashScopeSensitiveInspectionFailed(RuntimeException ex) {
        if (ex == null) {
            return false;
        }
        // 兼容：不强依赖具体异常类（例如 DashScopeApiException），但仍可识别其敏感词审核失败信号
        String className = ex.getClass().getSimpleName();
        String msg = safeMessage(ex);
        String full = className + " " + msg;
        return full.contains(SENSITIVE_HINT);
    }

    private static String safeMessage(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }
}

