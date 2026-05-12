package com.gen.ai.infrastructure.rag.extractor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 按知识类型（json/markdown/mysql）解析并返回对应 {@link KnowledgeExtractor} 的注册表工厂。
 */
@Component
@Slf4j
public class KnowledgeExtractorFactory {

    /** 键为小写的 {@link KnowledgeExtractor#supportType()}，值为 Spring 注入的提取器 Bean。 */
    private final Map<String, KnowledgeExtractor> extractorRegistry = new ConcurrentHashMap<>();

    /**
     * 收集容器中所有 {@link KnowledgeExtractor} 实现，按 {@code supportType()}（小写）注册。
     */
    public KnowledgeExtractorFactory(List<KnowledgeExtractor> extractors) {
        if (extractors != null) {
            for (KnowledgeExtractor extractor : extractors) {
                this.extractorRegistry.put(extractor.supportType().toLowerCase(), extractor);
            }
        }
        log.info(">>>> [Factory-Init] 知识解析厂工厂组装成功，当前已挂载的格式插槽账户: {}", extractorRegistry.keySet());
    }

    /**
     * 根据知识类型获取已注册的提取器。
     *
     * @param knowledgeType 非空，与配置一致
     * @return 对应格式的提取器
     * @throws IllegalArgumentException 类型未注册时抛出
     */
    public KnowledgeExtractor getExtractor(String knowledgeType) {
        if (knowledgeType == null || knowledgeType.isBlank()) {
            throw new IllegalArgumentException(">>>> [Factory-Error] 传入的知识库解析格式不允许为空！");
        }

        KnowledgeExtractor targetExtractor = this.extractorRegistry.get(knowledgeType.toLowerCase());

        if (targetExtractor == null) {
            throw new IllegalArgumentException(">>>> [Factory-Error] 全局未探测到能够处理该格式的知识解析插槽: " + knowledgeType);
        }

        return targetExtractor;
    }
}
