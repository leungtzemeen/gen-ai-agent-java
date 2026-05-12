package com.gen.ai.infrastructure.rag.model;

import org.springframework.core.io.Resource;

import lombok.Builder;
import lombok.Data;

/**
 * 知识库解析阶段的统一入参：由 {@link com.gen.ai.infrastructure.rag.context.KnowledgeContextStrategy} 根据存储配置与类型组装，
 * 再交给 {@link com.gen.ai.infrastructure.rag.extractor.KnowledgeExtractor#extract(KnowledgeLocationContext)} 读取文件或后续扩展的数据源。
 */
@Data
@Builder
public class KnowledgeLocationContext {

    /** Markdown 为目录资源、JSON 为单文件资源；MySQL 模式可为 {@code null}。 */
    private Resource fileResource;

    /** 业务类目，用于 JSON 行过滤、Markdown 辅助元数据等。 */
    private String bizCategory;
}
