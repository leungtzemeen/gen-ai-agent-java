package com.gen.ai;

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

    public static void main(String[] args) {
        SpringApplication.run(GenAiAgentApplication.class, args);
    }
}
