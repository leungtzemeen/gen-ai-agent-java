package com.gen.ai.web;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 限制 {@code /ai/admin/**} 仅可由本机（loopback）访问，便于本机 {@code curl}，避免公网误触运维接口。
 * <p>
 * 若经反向代理访问，{@code getRemoteAddr()} 常为代理 IP，此时应在网关做 IP 限制或信任 {@code X-Forwarded-For}
 *（须单独加固，此处不解析转发头以免误放行）。
 */
@Slf4j
public final class LocalhostOnlyAdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler)
            throws Exception {
        if (isLoopback(request.getRemoteAddr())) {
            return true;
        }
        log.warn(
                ">>>> [System-Admin] 拒绝非本机访问 path={} remoteAddr={}",
                request.getRequestURI(),
                request.getRemoteAddr());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Admin endpoints are only allowed from localhost (loopback).");
        return false;
    }

    static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
