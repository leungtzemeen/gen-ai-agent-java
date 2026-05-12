package com.gen.ai.infrastructure.rag.ingestion;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.pipeline.KnowledgeImportPipeline;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量灌库编排器：根据 {@code knowledgeType} 选择已注册的 {@link KnowledgeIngestionStrategy}，
 * 将候选 {@link Document} 列表写入 {@link org.springframework.ai.vectorstore.VectorStore}（含增量删旧、去重等策略细节）。
 */
@Component
@Slf4j
public class KnowledgeVectorIngester {

    private final Map<String, KnowledgeIngestionStrategy> strategies;
    private final VectorStore vectorStore;
    private final StorageProperties storageProperties;
    private final KnowledgeDuplicateChecker duplicateChecker;

    /**
     * 将 Spring 注入的各类型灌库策略注册为按 {@link KnowledgeIngestionStrategy#supportType()} 索引的 Map（小写键）。
     */
    public KnowledgeVectorIngester(
            List<KnowledgeIngestionStrategy> strategyList,
            VectorStore vectorStore,
            StorageProperties storageProperties,
            KnowledgeDuplicateChecker duplicateChecker) {
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.storageProperties = Objects.requireNonNull(storageProperties);
        this.duplicateChecker = Objects.requireNonNull(duplicateChecker);
        this.strategies = strategyList.stream()
                .collect(Collectors.toConcurrentMap(
                        s -> s.supportType().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> {
                            log.warn(
                                    ">>>> [RAG-Ingest] 重复注册策略 [{}]，保留 {}",
                                    a.supportType(),
                                    a.getClass().getSimpleName());
                            return a;
                        },
                        ConcurrentHashMap::new));
        log.info(">>>> [RAG-Ingest] 已注册灌库策略: {}", strategies.keySet());
    }

    /**
     * 按知识类型分发到对应策略执行灌库。
     *
     * @param knowledgeType 与配置一致，如 {@code json}、{@code markdown}、{@code mysql}
     * @param candidates    由 {@link KnowledgeImportPipeline} 产出的待灌库文档
     * @return {@code true} 表示向量库在本次调用中发生了写入或删除
     * @throws IllegalArgumentException 未注册对应类型的策略时抛出
     */
    public boolean ingest(String knowledgeType, List<Document> candidates) {
        Objects.requireNonNull(knowledgeType, "knowledgeType");
        KnowledgeIngestionStrategy strategy = strategies.get(knowledgeType.toLowerCase(Locale.ROOT));
        if (strategy == null) {
            throw new IllegalArgumentException("未注册知识类型的灌库策略: " + knowledgeType);
        }
        return strategy.ingest(vectorStore, storageProperties, duplicateChecker, candidates);
    }
}
