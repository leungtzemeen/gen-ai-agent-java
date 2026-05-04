package com.gen.ai.infrastructure.mcp;

import java.nio.file.Path;

import org.springframework.core.env.Environment;

/**
 * 解析 WiseLink MCP（stdio）工作区根路径，与 {@code app.storage.root} 约定对齐。
 */
final class WiseLinkMcpWorkspacePaths {

    private WiseLinkMcpWorkspacePaths() {}

    /**
     * 解析结果供运维日志标注来源（仅 config / property key 名，不输出敏感内容）。
     *
     * @param sourceConfigKey 主来源配置键，如 {@code WISELINK_MCP_WORKSPACE_ROOT} 或 {@code app.storage.root}
     */
    record WorkspaceRootResolution(Path absolutePath, String sourceConfigKey) {}

    /**
     * 优先 {@code WISELINK_MCP_WORKSPACE_ROOT}（环境变量或配置属性），否则按 {@code app.storage.root} 相对当前
     * {@code user.dir} 展开为绝对路径。
     */
    static Path resolveWorkspaceRoot(Environment environment) {
        return resolveWorkspaceRootWithSource(environment).absolutePath();
    }

    static WorkspaceRootResolution resolveWorkspaceRootWithSource(Environment environment) {
        String explicit = environment.getProperty("WISELINK_MCP_WORKSPACE_ROOT");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath().normalize();
            return new WorkspaceRootResolution(path, "WISELINK_MCP_WORKSPACE_ROOT");
        }
        String storageRoot = environment.getProperty("app.storage.root", "./data/gen-ai-agent");
        Path p = Path.of(storageRoot);
        if (!p.isAbsolute()) {
            String userDir = environment.getProperty("user.dir", System.getProperty("user.dir", "."));
            p = Path.of(userDir).resolve(p).normalize();
        }
        Path absolute = p.toAbsolutePath().normalize();
        return new WorkspaceRootResolution(absolute, "app.storage.root");
    }
}
