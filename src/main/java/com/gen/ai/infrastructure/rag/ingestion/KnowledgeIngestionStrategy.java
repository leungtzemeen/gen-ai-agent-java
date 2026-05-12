package com.gen.ai.infrastructure.rag.ingestion;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import com.gen.ai.config.StorageProperties;

/**
 * 知识灌库策略：按 {@code app.knowledge.type} 决定写入向量库前的删旧、跳过未变更数据、以及最终 {@code accept} 行为。
 */
public interface KnowledgeIngestionStrategy {

    /** @return 支持的知识类型标识，与配置 {@code app.knowledge.type} 一致（如 {@code json}） */
    String supportType();

    /**
     * 将候选文档写入向量库；实现类可调用 {@link KnowledgeDuplicateChecker} 与按主键/来源删除旧向量。
     *
     * @param vectorStore        目标向量存储
     * @param storageProperties  应用存储与 RAG 相关配置
     * @param duplicateChecker   基于现有向量数据的只读去重探测
     * @param candidates         待处理文档列表，可能为空
     * @return {@code true} 表示本次对向量库执行了删改/写入（含 {@code delete}/{@code accept}），可用于决定是否落盘
     */
    boolean ingest(
            VectorStore vectorStore,
            StorageProperties storageProperties,
            KnowledgeDuplicateChecker duplicateChecker,
            List<Document> candidates);
}
