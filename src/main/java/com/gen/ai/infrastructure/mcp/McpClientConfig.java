package com.gen.ai.infrastructure.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpSyncToolsChangeEventEmmiter;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

import com.gen.ai.wiselink.WiseLinkToolFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 3.0 — MCP：外层仅装配与 WiseLink 共存的合并回调；Stdio MCP 客户端与全部 MCP Bean 仅在
 * {@code spring.ai.mcp.client.enabled=true} 时由嵌套配置注册，{@code enabled=false} 时不创建任何 MCP 相关 Bean、
 * 不绑定 {@link McpClientCommonProperties}。
 * <p>
 * SSE / Streamable-HTTP 已在 {@link com.gen.ai.GenAiAgentApplication} 排除；默认
 * {@code McpClientAutoConfiguration} / {@code McpToolCallbackAutoConfiguration} 亦排除，由嵌套类按官方逻辑创建
 * {@link McpSyncClient} 与 {@link SyncMcpToolCallbackProvider}，失败时降级为空实现，主应用继续启动。
 */
@Configuration
@Slf4j
public class McpClientConfig {

    @Bean
    ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks(
            WiseLinkToolFactory wiseLinkToolFactory,
            ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider) {
        return new ShoppingGuideMergedToolCallbacks(wiseLinkToolFactory, syncMcpToolCallbackProvider);
    }

    /**
     * 在 {@link StdioTransportAutoConfiguration} 装配 stdio 传输层前后注入 MCP 子进程环境变量与工作目录（见
     * {@link WiseLinkMcpStdioLaunchBeanPostProcessor}）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
    static WiseLinkMcpStdioLaunchBeanPostProcessor wiseLinkMcpStdioLaunchBeanPostProcessor(Environment environment) {
        return new WiseLinkMcpStdioLaunchBeanPostProcessor(environment);
    }

    /**
     * 每次请求重新拉取 MCP 侧 {@link ToolCallback}（与 {@link SyncMcpToolCallbackProvider} 的缓存/刷新策略一致），
     * 再与 WiseLink 本地工具拼接，供 {@link com.gen.ai.application.shopping.AiShoppingGuideApp} 注入使用。
     */
    public static final class ShoppingGuideMergedToolCallbacks {

        private final WiseLinkToolFactory wiseLinkToolFactory;
        private final ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider;

        ShoppingGuideMergedToolCallbacks(
                WiseLinkToolFactory wiseLinkToolFactory,
                ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider) {
            this.wiseLinkToolFactory = wiseLinkToolFactory;
            this.syncMcpToolCallbackProvider = syncMcpToolCallbackProvider;
        }

        /**
         * @param conversationId 当前会话标识，用于合并工具列表的运维日志与 MCP 侧对照；可为 null（记为 default）
         */
        public List<ToolCallback> allToolCallbacks(String conversationId) {
            String cid =
                    conversationId == null || conversationId.isBlank() ? "default" : conversationId;
            List<ToolCallback> merged = new ArrayList<>(wiseLinkToolFactory.toolCallbacks());
            SyncMcpToolCallbackProvider mcp = syncMcpToolCallbackProvider.getIfAvailable();
            if (mcp == null) {
                log.debug("MCP ToolCallbackProvider 未注册（spring.ai.mcp.client.enabled=false 或未装配）");
                logMergedTools(cid, merged);
                return Collections.unmodifiableList(merged);
            }
            try {
                ToolCallback[] fromMcp = mcp.getToolCallbacks();
                if (fromMcp != null && fromMcp.length > 0) {
                    merged.addAll(Arrays.asList(fromMcp));
                }
            } catch (Exception ex) {
                log.error(
                        "[WiseLink-MCP] 运行时获取 MCP 工具列表失败，本次请求仅使用 WiseLink 本地工具。conversationId={} 原因: {}",
                        cid,
                        ex.toString(),
                        ex);
            }
            logMergedTools(cid, merged);
            return Collections.unmodifiableList(merged);
        }

        private static void logMergedTools(String conversationId, List<ToolCallback> merged) {
            String names =
                    merged.stream().map(cb -> cb.getToolDefinition().name()).collect(Collectors.joining(", "));
            log.info(
                    ">>>> [WiseLink-MCP-Tools] mergedToolCallbacks conversationId={} total={} toolNames=[{}]",
                    conversationId,
                    merged.size(),
                    names);
        }
    }

    /**
     * {@code spring.ai.mcp.client.enabled=true} 时整组注册；为 false 时本配置类不解析，实现 MCP 侧「零 Bean」。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
    @AutoConfigureAfter(StdioTransportAutoConfiguration.class)
    @EnableConfigurationProperties(McpClientCommonProperties.class)
    @Slf4j
    static class WiseLinkStdioMcpBeans {

        private static final Path MAP_SERVER_MCP_SANDBOX =
                Path.of("data", "gen-ai-agent", "mcp-map-sandbox");

        @Bean("wiseLinkMcpStdioProcessManager")
        WiseLinkMcpStdioProcessManager wiseLinkMcpStdioProcessManager() {
            return new WiseLinkMcpStdioProcessManager();
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> wiseLinkMcpToolDiscoveryLogger(
                SyncMcpToolCallbackProvider syncMcpToolCallbackProvider) {
            return event -> {
                try {
                    Files.createDirectories(MAP_SERVER_MCP_SANDBOX);
                } catch (IOException ex) {
                    log.warn("[WiseLink-MCP] 无法创建 map-server 沙箱目录 {}: {}", MAP_SERVER_MCP_SANDBOX, ex.getMessage());
                }

                try {
                    ToolCallback[] tools = syncMcpToolCallbackProvider.getToolCallbacks();
                    if (tools == null || tools.length == 0) {
                        log.warn("[WiseLink-MCP] MCP 已装配但未发现任何外部工具（请检查 stdio 与 map-server 子进程）");
                        return;
                    }
                    List<String> names = Arrays.stream(tools)
                            .map(t -> t.getToolDefinition().name())
                            .collect(Collectors.toList());
                    log.info("[WiseLink-MCP] 已发现并注册外部工具列表: {}", names);
                } catch (Exception ex) {
                    log.error("[WiseLink-MCP] 启动期枚举 MCP 工具失败（应用已继续运行）", ex);
                }
            };
        }

        @Bean
        McpSyncToolsChangeEventEmmiter mcpSyncToolsChangeEventEmmiter(ApplicationEventPublisher publisher) {
            return new McpSyncToolsChangeEventEmmiter(publisher);
        }

        @Bean
        McpSyncClientConfigurer mcpSyncClientConfigurer(ObjectProvider<McpSyncClientCustomizer> customizers) {
            return new McpSyncClientConfigurer(customizers.orderedStream().toList());
        }

        @Bean(name = "mcpSyncClients")
        List<McpSyncClient> mcpSyncClients(
                McpSyncClientConfigurer configurer,
                McpClientCommonProperties commonProperties,
                ObjectProvider<List<NamedClientMcpTransport>> transportListsProvider,
                WiseLinkMcpStdioProcessManager wiseLinkMcpStdioProcessManager) {
            try {
                List<NamedClientMcpTransport> transports = transportListsProvider.stream()
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .toList();
                if (transports.isEmpty()) {
                    log.warn("[WiseLink-MCP] 未注册任何 NamedClientMcpTransport（请检查 spring.ai.mcp.client.stdio.connections）");
                    return List.of();
                }

                Duration requestTimeout =
                        Objects.requireNonNullElse(commonProperties.getRequestTimeout(), Duration.ofSeconds(5));
                String implName = Objects.requireNonNullElse(commonProperties.getName(), "wiselink-mcp-client");
                String implVersion = Objects.requireNonNullElse(commonProperties.getVersion(), "1.0.0");

                List<McpSyncClient> clients = new ArrayList<>();
                for (NamedClientMcpTransport named : transports) {
                    McpSchema.Implementation clientInfo = new McpSchema.Implementation(
                            implName + "-" + named.name(), named.name(), implVersion);
                    McpClient.SyncSpec spec = McpClient.sync(named.transport())
                            .clientInfo(clientInfo)
                            .requestTimeout(requestTimeout);
                    spec = configurer.configure(named.name(), spec);
                    McpSyncClient client = spec.build();
                    if (commonProperties.isInitialized()) {
                        client.initialize();
                    }
                    wiseLinkMcpStdioProcessManager.registerFromStdioTransport(named.transport());
                    clients.add(client);
                }
                return List.copyOf(clients);
            } catch (Exception ex) {
                log.error(
                        "[WiseLink-MCP] MCP 同步客户端（Stdio）初始化失败，已跳过外部工具；主应用继续启动。原因: {}",
                        ex.toString(),
                        ex);
                return List.of();
            }
        }

        @Bean(destroyMethod = "close")
        @DependsOn("wiseLinkMcpStdioProcessManager")
        McpClientAutoConfiguration.CloseableMcpSyncClients makeSyncClientsClosable(
                @Qualifier("mcpSyncClients") List<McpSyncClient> clients) {
            return new McpClientAutoConfiguration.CloseableMcpSyncClients(clients);
        }

        @Bean
        @ConditionalOnMissingBean(McpToolNamePrefixGenerator.class)
        McpToolNamePrefixGenerator defaultMcpToolNamePrefixGenerator() {
            return McpToolNamePrefixGenerator.noPrefix();
        }

        @Bean(name = "mcpToolCallbacks")
        SyncMcpToolCallbackProvider mcpToolCallbacks(
                ObjectProvider<McpToolFilter> toolFilter,
                @Qualifier("mcpSyncClients") ObjectProvider<List<McpSyncClient>> syncClients,
                ObjectProvider<McpToolNamePrefixGenerator> prefix,
                ObjectProvider<ToolContextToMcpMetaConverter> metaConverter) {
            try {
                List<McpSyncClient> clients = syncClients.getIfAvailable(List::of);
                SyncMcpToolCallbackProvider.Builder builder = SyncMcpToolCallbackProvider.builder().mcpClients(clients);
                toolFilter.ifAvailable(builder::toolFilter);
                prefix.ifAvailable(builder::toolNamePrefixGenerator);
                metaConverter.ifAvailable(builder::toolContextToMcpMetaConverter);
                return builder.build();
            } catch (Exception ex) {
                log.error(
                        "[WiseLink-MCP] SyncMcpToolCallbackProvider 构建失败，已降级为空工具列表；主应用继续启动。原因: {}",
                        ex.toString(),
                        ex);
                return SyncMcpToolCallbackProvider.builder().mcpClients(List.of()).build();
            }
        }
    }
}
