package com.gen.ai.infrastructure.rag.context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

import lombok.extern.slf4j.Slf4j;

/**
 * 按 {@link KnowledgeContextStrategy#supportType()} 注册各数据源凭证构建策略，并对外提供统一入口 {@link #createAndBuildContext}。
 */
@Component
@Slf4j
public class KnowledgeContextStrategyFactory {

    /** 键为小写知识类型（如 {@code json}），值为对应策略 Bean。 */
    private final Map<String, KnowledgeContextStrategy> strategyRegistry = new ConcurrentHashMap<>();

    /** 注入并注册所有 {@link KnowledgeContextStrategy} 实现。 */
    public KnowledgeContextStrategyFactory(List<KnowledgeContextStrategy> strategies) {
        if (strategies != null) {
            for (KnowledgeContextStrategy strategy : strategies) {
                this.strategyRegistry.put(strategy.supportType().toLowerCase(), strategy);
            }
        }
        log.info(">>>> [Factory-Init] 凭证策略工厂组装成功，当前已激活挂载的物理插槽账户: {}", strategyRegistry.keySet());
    }

    /**
     * 根据知识类型选择策略并构建 {@link KnowledgeLocationContext}。
     *
     * @param knowledgeType      与配置一致，非空
     * @param category           业务类目
     * @param storageProperties  存储路径等配置
     * @param resourceLoader     用于解析 {@code file:} 资源
     */
    public KnowledgeLocationContext createAndBuildContext(
            String knowledgeType,
            String category,
            StorageProperties storageProperties,
            ResourceLoader resourceLoader) {

        if (knowledgeType == null || knowledgeType.isBlank()) {
            throw new IllegalArgumentException(">>>> [Factory-Error] 传入的知识库策略类型不允许为空！");
        }

        KnowledgeContextStrategy targetStrategy = this.strategyRegistry.get(knowledgeType.toLowerCase());

        if (targetStrategy == null) {
            throw new IllegalArgumentException(">>>> [Factory-Error] 全局未探测到能够处理该格式的凭证策略插槽: " + knowledgeType);
        }

        log.debug(">>>> [Factory-Route] 成功路由至特定策略类: [{}]，开始像素级组装提货凭证...", targetStrategy.getClass().getSimpleName());

        return targetStrategy.buildContext(category, storageProperties, resourceLoader);
    }
}
