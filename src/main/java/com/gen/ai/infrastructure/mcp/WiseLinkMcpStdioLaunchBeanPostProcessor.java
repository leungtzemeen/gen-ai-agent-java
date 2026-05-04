package com.gen.ai.infrastructure.mcp;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties.Parameters;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 在 Spring 创建 stdio 传输层之后、业务使用前：
 * <ul>
 *   <li>为 {@code wiselink-mcp-ecosystem} 补充环境变量 {@code WISELINK_MCP_WORKSPACE_ROOT}（仅子进程可见，供 MCP Server
 *       解析 exports/downloads 根路径）；</li>
 *   <li>为该连接替换 {@link StdioClientTransport} 为带 {@link ProcessBuilder#directory(java.io.File)} 的实现，与上述根路径一致。</li>
 * </ul>
 */
@Slf4j
final class WiseLinkMcpStdioLaunchBeanPostProcessor implements BeanPostProcessor {

    static final String WISELINK_STDIO_CONNECTION_NAME = "wiselink-mcp-ecosystem";
    private static final String WORKSPACE_ENV = "WISELINK_MCP_WORKSPACE_ROOT";
    /** 与 application.yml 中 stdio.connections 路径一致，便于运维对照配置 */
    private static final String CONFIG_KEY_CONNECTION_ENV =
            "spring.ai.mcp.client.stdio.connections." + WISELINK_STDIO_CONNECTION_NAME + ".env." + WORKSPACE_ENV;

    private final Environment environment;

    WiseLinkMcpStdioLaunchBeanPostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof McpStdioClientProperties props) {
            enrichStdioProperties(props);
            return bean;
        }
        if ("stdioTransports".equals(beanName) && bean instanceof List<?> raw) {
            return rewriteStdioTransportListIfNeeded(raw);
        }
        return bean;
    }

    private void enrichStdioProperties(McpStdioClientProperties props) {
        Map<String, Parameters> connections = props.getConnections();
        if (connections == null) {
            return;
        }
        Parameters p = connections.get(WISELINK_STDIO_CONNECTION_NAME);
        if (p == null) {
            return;
        }
        Map<String, String> subEnv = new HashMap<>();
        if (p.env() != null) {
            subEnv.putAll(p.env());
        }
        String prior = subEnv.get(WORKSPACE_ENV);
        if (prior != null && !prior.isBlank()) {
            Path absolute = Path.of(prior).toAbsolutePath().normalize();
            log.info(
                    ">>>> [WiseLink-MCP-Workspace] stdio connection [{}]: {} already set for child process; absolutePath={}; source=config key {}",
                    WISELINK_STDIO_CONNECTION_NAME,
                    WORKSPACE_ENV,
                    absolute,
                    CONFIG_KEY_CONNECTION_ENV);
        } else {
            WiseLinkMcpWorkspacePaths.WorkspaceRootResolution resolution =
                    WiseLinkMcpWorkspacePaths.resolveWorkspaceRootWithSource(environment);
            subEnv.put(WORKSPACE_ENV, resolution.absolutePath().toString());
            log.info(
                    ">>>> [WiseLink-MCP-Workspace] stdio connection [{}]: injecting {} for child process; absolutePath={}; source=config key {}",
                    WISELINK_STDIO_CONNECTION_NAME,
                    WORKSPACE_ENV,
                    resolution.absolutePath(),
                    resolution.sourceConfigKey());
        }
        connections.put(WISELINK_STDIO_CONNECTION_NAME, new Parameters(p.command(), p.args(), Map.copyOf(subEnv)));
    }

    private List<NamedClientMcpTransport> rewriteStdioTransportListIfNeeded(List<?> raw) {
        if (raw.isEmpty() || !(raw.get(0) instanceof NamedClientMcpTransport)) {
            @SuppressWarnings("unchecked")
            List<NamedClientMcpTransport> passThrough = (List<NamedClientMcpTransport>) (List<?>) raw;
            return passThrough;
        }
        List<NamedClientMcpTransport> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            NamedClientMcpTransport n = (NamedClientMcpTransport) o;
            if (WISELINK_STDIO_CONNECTION_NAME.equals(n.name()) && n.transport() instanceof StdioClientTransport base) {
                ServerParametersAndMapper extracted = extractStdioInternals(base);
                Path root = workspaceRootForProcess(extracted.params());
                logWorkspaceTransportBound(extracted.params(), root);
                out.add(new NamedClientMcpTransport(
                        n.name(),
                        new WiseLinkMcpWorkspaceRootStdioTransport(
                                extracted.params(), extracted.jsonMapper(), root)));
            } else {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    /** 与即将注入子进程的环境变量一致，避免 {@code user.dir}（directory）与 exports 根路径各算各的。 */
    private Path workspaceRootForProcess(ServerParameters params) {
        Map<String, String> subprocessEnv = params.getEnv();
        if (subprocessEnv != null) {
            String fromSubprocess = subprocessEnv.get(WORKSPACE_ENV);
            if (fromSubprocess != null && !fromSubprocess.isBlank()) {
                return Path.of(fromSubprocess).toAbsolutePath().normalize();
            }
        }
        return WiseLinkMcpWorkspacePaths.resolveWorkspaceRoot(environment);
    }

    /** 子进程实际启动时尚未发生，此处记录与 {@link ProcessBuilder#directory} 及子进程 env 对齐后的运维信息。 */
    private void logWorkspaceTransportBound(ServerParameters params, Path processBuilderDirectory) {
        Map<String, String> subprocessEnv = params.getEnv();
        String raw = subprocessEnv == null ? null : subprocessEnv.get(WORKSPACE_ENV);
        boolean envSet = raw != null && !raw.isBlank();
        if (envSet) {
            Path envAbsolute = Path.of(raw).toAbsolutePath().normalize();
            log.info(
                    ">>>> [WiseLink-MCP-Workspace] stdio transport ready [{}]: {} is set; absolutePath={}; ProcessBuilder.directory={}",
                    WISELINK_STDIO_CONNECTION_NAME,
                    WORKSPACE_ENV,
                    envAbsolute,
                    processBuilderDirectory);
        } else {
            log.info(
                    ">>>> [WiseLink-MCP-Workspace] stdio transport ready [{}]: {} unset in ServerParameters — child uses MCP Server default for that env var; ProcessBuilder.directory={} (fallback: WISELINK_MCP_WORKSPACE_ROOT / app.storage.root)",
                    WISELINK_STDIO_CONNECTION_NAME,
                    WORKSPACE_ENV,
                    processBuilderDirectory);
        }
    }

    private static ServerParametersAndMapper extractStdioInternals(StdioClientTransport transport) {
        try {
            Field paramsField = StdioClientTransport.class.getDeclaredField("params");
            Field mapperField = StdioClientTransport.class.getDeclaredField("jsonMapper");
            paramsField.trySetAccessible();
            mapperField.trySetAccessible();
            return new ServerParametersAndMapper((ServerParameters) paramsField.get(transport), (McpJsonMapper) mapperField.get(transport));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取 StdioClientTransport 内部字段（SDK 不兼容）", ex);
        }
    }

    private record ServerParametersAndMapper(ServerParameters params, McpJsonMapper jsonMapper) {}
}
