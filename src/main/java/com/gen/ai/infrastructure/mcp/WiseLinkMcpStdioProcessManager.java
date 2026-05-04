package com.gen.ai.infrastructure.mcp;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

import lombok.extern.slf4j.Slf4j;

/**
 * 跟踪 Stdio MCP 子进程并在 JVM / Spring 关闭时显式销毁，避免 map-server 等子进程残留。
 * 仅当 {@code spring.ai.mcp.client.enabled=true} 时由 {@link McpClientConfig} 注册为 Bean。
 */
@Slf4j
public class WiseLinkMcpStdioProcessManager {

    private static final Duration DESTROY_WAIT = Duration.ofSeconds(2);

    private final List<Process> processes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);

    /**
     * 在 {@link io.modelcontextprotocol.client.McpSyncClient#initialize()} 之后调用，此时 {@link StdioClientTransport} 已启动子进程。
     */
    public void registerFromStdioTransport(McpClientTransport transport) {
        Process p = extractStdioProcess(transport);
        if (p != null && p.isAlive()) {
            processes.add(p);
        }
    }

    @PostConstruct
    void registerJvmShutdownHook() {
        Thread hook = new Thread(this::shutdownAll, "WiseLink-McpStdioProcessShutdownHook");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    @PreDestroy
    void preDestroy() {
        shutdownAll();
    }

    void shutdownAll() {
        if (!shutdownDone.compareAndSet(false, true)) {
            return;
        }
        for (Process process : processes) {
            destroyWithSecurityLog(process);
        }
        processes.clear();
    }

    private void destroyWithSecurityLog(Process process) {
        if (process == null) {
            return;
        }
        long pid = process.pid();
        try {
            if (!process.isAlive()) {
                logSecurityInfo(pid, "进程已结束（跳过）");
                return;
            }
            logSecurityInfo(pid, "优雅关闭中...");
            process.destroy();
            boolean exited = process.waitFor(DESTROY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited && process.isAlive()) {
                logSecurityInfo(pid, "强制关闭中...");
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logSecurityWarn(pid, "等待被中断，转为强制关闭 — " + ex);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception ex) {
            logSecurityWarn(pid, "关闭异常 — " + ex);
            if (process.isAlive()) {
                try {
                    process.destroyForcibly();
                } catch (Exception forceEx) {
                    logSecurityWarn(pid, "强制关闭失败 — " + forceEx);
                }
            }
        }
    }

    private static void logSecurityInfo(long pid, String status) {
        log.info(">>>> [WiseLink-Security] 正在关闭 MCP 子进程 [PID: {}] - 状态: {}", pid, status);
    }

    private static void logSecurityWarn(long pid, String status) {
        log.warn(">>>> [WiseLink-Security] 正在关闭 MCP 子进程 [PID: {}] - 状态: {}", pid, status);
    }

    private static Process extractStdioProcess(McpClientTransport transport) {
        if (!(transport instanceof StdioClientTransport)) {
            return null;
        }
        try {
            Field f = StdioClientTransport.class.getDeclaredField("process");
            f.setAccessible(true);
            return (Process) f.get(transport);
        } catch (ReflectiveOperationException ex) {
            log.warn(
                    "[WiseLink-MCP] 无法通过反射读取 StdioClientTransport 子进程（SDK 结构可能已变更）: {}",
                    ex.toString());
            return null;
        }
    }
}
