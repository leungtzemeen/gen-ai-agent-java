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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.tool.PerRequestToolBudgetToolCallback;
import com.gen.ai.infrastructure.tool.ToolCallbackComposition;
import com.gen.ai.wiselink.WiseLinkToolFactory;
import com.gen.ai.wiselink.security.tool.VipRestrictedToolCallback;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 3.0 — MCP：外层仅装配与 WiseLink 共存的合并回调；Stdio MCP 客户端与全部 MCP Bean 仅在
 * {@code spring.ai.mcp.client.enabled=true} 时由嵌套配置注册，{@code enabled=false}
 * 时不创建任何 MCP 相关 Bean、
 * 不绑定 {@link McpClientCommonProperties}。
 * <p>
 * SSE / Streamable-HTTP 已在 {@link com.gen.ai.GenAiAgentApplication} 排除；默认
 * {@code McpClientAutoConfiguration} / {@code McpToolCallbackAutoConfiguration}
 * 亦排除，由嵌套类按官方逻辑创建
 * {@link McpSyncClient} 与
 * {@link SyncMcpToolCallbackProvider}，失败时降级为空实现，主应用继续启动。
 */
@Configuration
@Slf4j
public class McpClientConfig {

    @Bean
    ShoppingGuideMergedToolCallbacks shoppingGuideMergedToolCallbacks(
            WiseLinkToolFactory wiseLinkToolFactory,
            ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider,
            StorageProperties storageProperties) {
        return new ShoppingGuideMergedToolCallbacks(wiseLinkToolFactory, syncMcpToolCallbackProvider, storageProperties);
    }

    /**
     * 每次请求重新拉取 MCP 侧 {@link ToolCallback}（与 {@link SyncMcpToolCallbackProvider}
     * 的缓存/刷新策略一致），
     * 再与 WiseLink 本地工具拼接，供
     * {@link com.gen.ai.application.shopping.AiShoppingGuideApp} 注入使用。
     * <p>
     * {@link #allToolCallbacks(AtomicInteger)} 的计数器须在单次 {@code ChatClient} 调用链内复用，以实现「本请求
     * 工具 leaf 总次数」上限。
     */
    public static final class ShoppingGuideMergedToolCallbacks {

        private final WiseLinkToolFactory wiseLinkToolFactory;
        private final ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider;
        private final StorageProperties storageProperties;

        ShoppingGuideMergedToolCallbacks(
                WiseLinkToolFactory wiseLinkToolFactory,
                ObjectProvider<SyncMcpToolCallbackProvider> syncMcpToolCallbackProvider,
                StorageProperties storageProperties) {
            this.wiseLinkToolFactory = wiseLinkToolFactory;
            this.syncMcpToolCallbackProvider = syncMcpToolCallbackProvider;
            this.storageProperties = storageProperties;
        }

        public List<ToolCallback> allToolCallbacks(AtomicInteger perRequestToolInvocations) {
            Objects.requireNonNull(perRequestToolInvocations, "perRequestToolInvocations");
            int maxChars = storageProperties.getMaxObservationChars();
            //调用工具次数上限
            int maxInvocations = storageProperties.getMaxToolInvocationsPerRequest();
            //是否启用预算保护  
            boolean budgetOn = maxInvocations > 0;
            //预算超限提示
            String budgetNotice = PerRequestToolBudgetToolCallback.BUDGET_EXCEEDED_NOTICE;
            //合并后的工具列表
            List<ToolCallback> rawMerged = new ArrayList<>();
            //本地工具(包括普通工具和VIP工具)
            if (wiseLinkToolFactory != null && wiseLinkToolFactory.toolCallbacks() != null) {
                for (ToolCallback localTool : wiseLinkToolFactory.toolCallbacks()) {
                    rawMerged.add(
                        //包装本地工具
                            wrapLocalTool(localTool, perRequestToolInvocations, maxChars, budgetOn, maxInvocations, budgetNotice));
                }
            }

            SyncMcpToolCallbackProvider mcp = syncMcpToolCallbackProvider.getIfAvailable();
            if (mcp == null) {
                log.debug("MCP ToolCallbackProvider 未注册（spring.ai.mcp.client.enabled=false 或未装配）");
                return Collections.unmodifiableList(rawMerged);
            }

            try {
                ToolCallback[] fromMcp = mcp.getToolCallbacks();
                if (fromMcp != null && fromMcp.length > 0) {
                    // B. MCP 工具：Truncation(Budget(leaf))（无 VIP 壳）
                    for (ToolCallback rawMcpTool : fromMcp) {
                        if (rawMcpTool != null) {
                            ToolCallback securedMcpTool = wrapMcpTool(
                                    rawMcpTool, perRequestToolInvocations, maxChars, budgetOn, maxInvocations, budgetNotice);
                            rawMerged.add(securedMcpTool);

                            log.debug(
                                    ">>>> [MCP-Dynamic-Secure] 成功为运行时 MCP 工具 [{}] 挂载截断/预算保护",
                                    rawMcpTool.getToolDefinition().name());
                        }
                    }
                }
            } catch (Exception ex) {
                log.error(
                        "[WiseLink-MCP] 运行时获取 MCP 工具列表失败，本次请求仅使用 WiseLink 本地工具。原因: {}",
                        ex.toString(),
                        ex);
            }

            log.info(">>>> [WiseLink-Tools] 全链路工具装配完毕，交付给 AI 的只读工具总计: {} 个", rawMerged.size());
            return Collections.unmodifiableList(rawMerged);
        }

        //包装本地工具
        private static ToolCallback wrapLocalTool(
                ToolCallback localTool,
                AtomicInteger perRequestToolInvocations,
                int maxChars,
                boolean budgetOn,
                int maxInvocations,
                String budgetNotice) {
            if (!budgetOn) {
                //不启用预算保护，只进行截断
                return ToolCallbackComposition.compose(localTool, ToolCallbackComposition.truncation(maxChars));
            }
            //装饰者:
            // 最里的那一层：预算 PerRequestToolBudgetToolCallback
            // 中间的那一层：VIP装饰 VipRestrictedToolCallback
            // 最外的那一层：截断 TruncationToolCallback
            //剥离VIP装饰
            ToolCallback rawLeaf = VipRestrictedToolCallback.unwrapVipDelegateOrSelf(localTool);
            //创建预算保护工具
            ToolCallback budgeted =
                    new PerRequestToolBudgetToolCallback(rawLeaf, perRequestToolInvocations, maxInvocations, budgetNotice);
            //重新包装VIP装饰
            ToolCallback withVip = VipRestrictedToolCallback.wrapWithVipIfMatches(budgeted, localTool);
            //重新包装截断装饰
            return ToolCallbackComposition.compose(withVip, ToolCallbackComposition.truncation(maxChars));
        }

        private static ToolCallback wrapMcpTool(
                ToolCallback rawMcpTool,
                AtomicInteger perRequestToolInvocations,
                int maxChars,
                boolean budgetOn,
                int maxInvocations,
                String budgetNotice) {
            if (!budgetOn) {
                return ToolCallbackComposition.compose(rawMcpTool, ToolCallbackComposition.truncation(maxChars));
            }
            ToolCallback budgeted =
                    new PerRequestToolBudgetToolCallback(rawMcpTool, perRequestToolInvocations, maxInvocations, budgetNotice);
            return ToolCallbackComposition.compose(budgeted, ToolCallbackComposition.truncation(maxChars));
        }
    }

    /**
     * {@code spring.ai.mcp.client.enabled=true} 时整组注册；为 false 时本配置类不解析，实现 MCP 侧「零
     * Bean」。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true")
    @AutoConfigureAfter(StdioTransportAutoConfiguration.class)
    @EnableConfigurationProperties(McpClientCommonProperties.class)
    @Slf4j
    static class WiseLinkStdioMcpBeans {

        private static final Path MAP_SERVER_MCP_SANDBOX = Path.of("data", "gen-ai-agent", "mcp-map-sandbox");

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
                    log.warn(
                            "[WiseLink-MCP] 未注册任何 NamedClientMcpTransport（请检查 spring.ai.mcp.client.stdio.connections）");
                    return List.of();
                }

                Duration requestTimeout = Objects.requireNonNullElse(commonProperties.getRequestTimeout(),
                        Duration.ofSeconds(5));
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
