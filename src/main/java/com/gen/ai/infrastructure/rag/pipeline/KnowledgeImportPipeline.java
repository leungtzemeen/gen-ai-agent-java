package com.gen.ai.infrastructure.rag.pipeline;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * 知识导入管道：串联「凭证策略 → 解析为 Document」，不写入 {@link org.springframework.ai.vectorstore.VectorStore}。
 * <p>
 * 具体装配委托 {@link com.gen.ai.infrastructure.rag.context.KnowledgeContextStrategyFactory} 与
 * {@link com.gen.ai.infrastructure.rag.extractor.KnowledgeExtractorFactory}，便于 {@link com.gen.ai.infrastructure.rag.service.RagDataService}
 * 只做编排与落库。
 */
@FunctionalInterface
public interface KnowledgeImportPipeline {

    /**
     * @param knowledgeType 与 {@code app.knowledge.type} 一致，如 {@code json}、{@code markdown}、{@code mysql}
     * @param bizCategory   业务类目（如 JSON 过滤用）；无类目语义时可传占位
     * @return 非 null；解析无结果时为 empty
     */
    List<Document> loadDocuments(String knowledgeType, String bizCategory);
}
