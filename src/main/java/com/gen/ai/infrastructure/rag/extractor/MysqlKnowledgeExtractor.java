package com.gen.ai.infrastructure.rag.extractor;

import java.util.Collections;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;

import lombok.RequiredArgsConstructor;

/**
 * MySQL 知识提取器（占位）：未来根据 {@link KnowledgeLocationContext#getBizCategory()} 查询数据库并映射为 {@link Document}；
 * 当前返回空列表，不改变启动与灌库流程。
 */
@Component
@RequiredArgsConstructor
public class MysqlKnowledgeExtractor implements KnowledgeExtractor {

    @Override
    public String supportType() {
        return "mysql";
    }

    /** 占位实现：未接入 Mapper 前始终返回空列表。 */
    @Override
    public List<Document> extract(KnowledgeLocationContext context) {
        return Collections.emptyList();
    }
}
