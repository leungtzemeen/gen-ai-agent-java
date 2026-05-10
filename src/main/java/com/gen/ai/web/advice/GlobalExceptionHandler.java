package com.gen.ai.web.advice;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    /**
     * 1. 拦截大模型底层的网络通讯与鉴权异常 (如 WebClientResponseException / OpenAiHttpException)
     */
    @ExceptionHandler({ WebClientResponseException.class })
    public ResponseEntity<String> handleAiNetworkException(Exception ex) {
        log.error(">>>> [WiseLink-Guard] 大模型云端通讯层爆发物理断路, 原因: {}", ex.getMessage());

        // 优雅自愈：告知前端用户，绝对不抛 500 崩溃
        String networkRefusal = "哎呀，WiseLink 现在的云端大脑开小差了（网络超时或账单波动），已主动触发安全断路保护。请直接整理现有数据结案。";
        return ResponseEntity.ok(networkRefusal);
    }

    /**
     * 2. 拦截并消灭因为工具调用死循环触发的迭代溢出异常 (TransientAiException / RuntimeException)
     * 完美贴合你现有的 handleRuntimeException 逻辑
     */
    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<String> handleAiLoopException(Exception ex) {
        // 关键特征审计：如果特征字符包含了 max iterations 说明是我们的钱包保护开关跳闸了！
        if (ex.getMessage() != null && ex.getMessage().contains("iterations")) {
            log.error(">>>> [WiseLink-Guard] 钱包保护锁成功起效！已成功阻断百炼/DeepSeek 的死循环空转。");

            String loopRefusal = "哎呀，该工具调用过于频繁，已主动触发安全断路保护。请直接整理现有数据结案。";
            return ResponseEntity.ok(loopRefusal);
        }

        // 如果是其他 AI 异常，则 fallback 到标准的 500
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal AI error");
    }

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
