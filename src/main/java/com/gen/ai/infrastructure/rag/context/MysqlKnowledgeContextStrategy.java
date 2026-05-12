package com.gen.ai.infrastructure.rag.context;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

/**
 * MySQL 知识库：不绑定本地文件资源，仅传递 {@code bizCategory} 等参数供后续数据访问层使用（当前提取器仍为占位）。
 */
@Component
public class MysqlKnowledgeContextStrategy implements KnowledgeContextStrategy {

    @Override
    public String supportType() {
        return "mysql";
    }

    /**
     * 仅设置业务类目，{@code fileResource} 为 {@code null}。
     */
    @Override
    public KnowledgeLocationContext buildContext(String category, StorageProperties storageProperties, ResourceLoader resourceLoader) {
        return KnowledgeLocationContext.builder()
                .bizCategory(category)
                .fileResource(null)
                .build();
    }
}
