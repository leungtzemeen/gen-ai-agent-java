package com.gen.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WiseLink AI（Gen AI Agent）Spring Boot 入口。
 */
@SpringBootApplication(
        exclude = {
            // 仅保留 Stdio：禁用 JDK HttpClient 的 SSE / Streamable-HTTP 传输自动配置
            SseHttpClientTransportAutoConfiguration.class,
            StreamableHttpHttpClientTransportAutoConfiguration.class,
            // 由 {@link com.gen.ai.infrastructure.mcp.McpClientConfig} 容错创建 MCP 客户端与 ToolCallbackProvider
            McpClientAutoConfiguration.class,
            McpToolCallbackAutoConfiguration.class
        })
public class GenAiAgentApplication {

    /** 与 application-dev.yml 中 map-server stdio 工作目录一致；须在 MCP 子进程启动前存在 */
    private static final Path MCP_MAP_SERVER_SANDBOX = Path.of("data", "gen-ai-agent", "mcp-map-sandbox");

    public static void main(String[] args) {
        try {
            Files.createDirectories(MCP_MAP_SERVER_SANDBOX);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 map-server MCP 沙箱目录: " + MCP_MAP_SERVER_SANDBOX, ex);
        }
        SpringApplication.run(GenAiAgentApplication.class, args);
    }
}
