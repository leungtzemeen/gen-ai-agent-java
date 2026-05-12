package com.gen.ai.infrastructure.rag.ingestion;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.model.RagDocumentMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Markdown 知识灌库策略：按 {@code source + file_hash} 判断是否已存在相同文件版本，未变更则跳过；
 * 有变更则按 {@code source}（文件名）删除旧切片再写入新切片。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MarkdownKnowledgeIngestionStrategy implements KnowledgeIngestionStrategy {

    private final RagIngestionSidecar ingestionSidecar;

    @Override
    public String supportType() {
        return "markdown";
    }

    /**
     * 过滤与向量库完全相同的 source/hash 后，对涉及的 source 执行删旧并 {@code accept} 新切片。
     */
    @Override
    @SuppressWarnings("unused")
    public boolean ingest(
            VectorStore vectorStore,
            StorageProperties storageProperties,
            KnowledgeDuplicateChecker duplicateChecker,
            List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }

        List<Document> validDocs = candidates.stream()
                .filter(doc -> !duplicateChecker.hasSameSourceAndHash(
                        meta(doc, RagDocumentMetadata.SOURCE),
                        meta(doc, RagDocumentMetadata.FILE_HASH)))
                .toList();

        if (validDocs.isEmpty()) {
            log.info(">>>> [RAG-Ingest] Markdown：本次无待灌库切片（均已存在相同 source+file_hash）。");
            return false;
        }

        Set<String> sources = new HashSet<>();
        for (Document doc : validDocs) {
            String s = meta(doc, RagDocumentMetadata.SOURCE);
            if (!s.isEmpty() && !"null".equals(s)) {
                sources.add(s);
            }
        }
        for (String source : sources) {
            ingestionSidecar.removeMarkdownSource(source);
            Filter.Expression exp = new FilterExpressionBuilder().eq(RagDocumentMetadata.SOURCE, source).build();
            vectorStore.delete(Objects.requireNonNull(exp));
        }

        vectorStore.accept(validDocs);
        for (Document doc : validDocs) {
            ingestionSidecar.putMarkdownFile(meta(doc, RagDocumentMetadata.SOURCE), meta(doc, RagDocumentMetadata.FILE_HASH));
        }
        ingestionSidecar.persistAfterMutation();
        log.info(">>>> [RAG-Ingest] Markdown：已灌入 {} 条切片。", validDocs.size());
        return true;
    }

    /** 读取文档 metadata 中的字符串，空或字面量 {@code null} 视为空串。 */
    private static String meta(Document doc, String key) {
        Object v = doc.getMetadata().get(key);
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v).trim();
        return "null".equals(s) ? "" : s;
    }
}
