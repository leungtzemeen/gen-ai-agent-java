package com.gen.ai.infrastructure.rag.extractor;

import java.util.List;

import org.springframework.ai.document.Document;

import com.gen.ai.infrastructure.rag.context.KnowledgeContextStrategy;
import com.gen.ai.infrastructure.rag.ingestion.KnowledgeVectorIngester;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;
import com.gen.ai.infrastructure.rag.pipeline.KnowledgeImportPipeline;

/**
 * 知识提取器：根据 {@link KnowledgeLocationContext}（文件路径、类目等）读取原始数据，转换为 Spring AI {@link Document} 列表；
 * 不负责写入向量库，由 {@link KnowledgeImportPipeline} 与 {@link KnowledgeVectorIngester} 串联。
 */
public interface KnowledgeExtractor {

    /** @return 支持的数据源类型，与 {@code app.knowledge.type} 一致（如 {@code json}） */
    String supportType();

    /**
     * 从上下文解析并生成待灌库的文档列表（可含切片、metadata）。
     *
     * @param context 由 {@link KnowledgeContextStrategy} 构建的提货凭证
     * @return 无数据或失败时返回空列表（具体行为见实现类）
     */
    List<Document> extract(KnowledgeLocationContext context);
}
