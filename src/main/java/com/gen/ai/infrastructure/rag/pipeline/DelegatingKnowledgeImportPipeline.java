package com.gen.ai.infrastructure.rag.pipeline;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.context.KnowledgeContextStrategyFactory;
import com.gen.ai.infrastructure.rag.extractor.KnowledgeExtractorFactory;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

import lombok.RequiredArgsConstructor;

/**
 * 默认知识导入管道实现：先由 {@link KnowledgeContextStrategyFactory} 构建 {@link KnowledgeLocationContext}，
 * 再由 {@link KnowledgeExtractorFactory} 取得对应 {@link com.gen.ai.infrastructure.rag.extractor.KnowledgeExtractor} 并 {@code extract}，返回文档列表。
 */
@Component
@RequiredArgsConstructor
public class DelegatingKnowledgeImportPipeline implements KnowledgeImportPipeline {

    private final KnowledgeContextStrategyFactory contextStrategyFactory;
    private final KnowledgeExtractorFactory extractorFactory;
    private final StorageProperties storageProperties;
    private final ResourceLoader resourceLoader;

    /** {@inheritDoc} */
    @Override
    public List<Document> loadDocuments(String knowledgeType, String bizCategory) {
        Objects.requireNonNull(knowledgeType, "knowledgeType");
        String category = bizCategory != null ? bizCategory : "";

        KnowledgeLocationContext context = contextStrategyFactory.createAndBuildContext(
                knowledgeType, category, storageProperties, resourceLoader);
        List<Document> documents = extractorFactory.getExtractor(knowledgeType).extract(context);
        return documents != null ? documents : List.of();
    }
}
