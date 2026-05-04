package com.gen.ai.infrastructure.mcp;

import java.nio.file.Path;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * 在启动 MCP stdio 子进程时为 {@link ProcessBuilder} 设置 {@code directory}，使子进程 {@code user.dir}
 * 与 {@code WISELINK_MCP_WORKSPACE_ROOT} 指向同一根目录，避免仅依赖环境变量与 cwd 不一致。
 */
final class WiseLinkMcpWorkspaceRootStdioTransport extends StdioClientTransport {

    private final Path workspaceRoot;

    WiseLinkMcpWorkspaceRootStdioTransport(
            ServerParameters params, McpJsonMapper jsonMapper, Path workspaceRoot) {
        super(params, jsonMapper);
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder pb = super.getProcessBuilder();
        pb.directory(workspaceRoot.toFile());
        return pb;
    }
}
