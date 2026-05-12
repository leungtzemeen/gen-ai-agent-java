package com.gen.ai.infrastructure.rag.context;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

/**
 * JSON 知识库：将配置目录下的 {@code goods_knowledge_base.json} 加载为单文件 {@link org.springframework.core.io.Resource}。
 */
@Component
public class JsonKnowledgeContextStrategy implements KnowledgeContextStrategy {

    @Override
    public String supportType() {
        return "json";
    }

    /**
     * 组装指向 {@code app.storage.rag-docs/goods_knowledge_base.json} 的凭证，并带上业务类目。
     */
    @Override
    public KnowledgeLocationContext buildContext(String category, StorageProperties storageProperties, ResourceLoader resourceLoader) {
        String jsonPath = "file:" + storageProperties.getStorage().getRagDocs() + "/goods_knowledge_base.json";
        return KnowledgeLocationContext.builder()
                .bizCategory(category)
                .fileResource(resourceLoader.getResource(jsonPath))
                .build();
    }
}
