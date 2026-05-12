package com.gen.ai.infrastructure.rag.ingestion;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 知识灌库策略（占位）：当前对候选文档直接 {@code accept}，未实现行级增量与按主键删旧；后续可与 JSON 策略对齐。
 */
@Component
@Slf4j
public class MysqlKnowledgeIngestionStrategy implements KnowledgeIngestionStrategy {

    @Override
    public String supportType() {
        return "mysql";
    }

    /** 直通写入向量库，不做去重与删旧（依赖上游 {@link com.gen.ai.infrastructure.rag.extractor.MysqlKnowledgeExtractor} 产出）。 */
    @Override
    @SuppressWarnings("unused")
    public boolean ingest(
            VectorStore vectorStore,
            StorageProperties storageProperties,
            KnowledgeDuplicateChecker duplicateChecker,
            List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        vectorStore.accept(candidates);
        log.info(">>>> [RAG-Ingest] MySQL：直通灌入 {} 条（未启用行级增量策略）。", candidates.size());
        return true;
    }
}
