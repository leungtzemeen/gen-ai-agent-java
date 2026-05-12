package com.gen.ai.infrastructure.rag.context;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

/**
 * Markdown 知识库：将 {@code app.storage.rag-docs} 目录作为资源根，供遍历 {@code .md} 文件。
 */
@Component
public class MarkdownKnowledgeContextStrategy implements KnowledgeContextStrategy {

    @Override
    public String supportType() {
        return "markdown";
    }

    /**
     * 组装指向 RAG 文档目录的文件夹资源与业务类目。
     */
    @Override
    public KnowledgeLocationContext buildContext(String category, StorageProperties storageProperties, ResourceLoader resourceLoader) {
        String folderPath = "file:" + storageProperties.getStorage().getRagDocs();
        return KnowledgeLocationContext.builder()
                .bizCategory(category)
                .fileResource(resourceLoader.getResource(folderPath))
                .build();
    }
}
