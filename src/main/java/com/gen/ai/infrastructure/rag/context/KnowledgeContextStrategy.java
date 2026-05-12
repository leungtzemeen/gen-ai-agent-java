package com.gen.ai.infrastructure.rag.context;

import org.springframework.core.io.ResourceLoader;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

/**
 * 知识库「提货凭证」构建策略：按数据源类型把配置中的路径等解析为 {@link KnowledgeLocationContext}，供提取器使用。
 */
public interface KnowledgeContextStrategy {

    /** @return 支持的数据源类型（如 {@code json}、{@code markdown}、{@code mysql}） */
    String supportType();

    /**
     * 根据当前类目与存储配置，构造本次解析所需的上下文（含 {@link org.springframework.core.io.Resource} 等）。
     */
    KnowledgeLocationContext buildContext(
            String category,
            StorageProperties storageProperties,
            ResourceLoader resourceLoader);
}
