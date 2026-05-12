package com.gen.ai.advisor;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 包装 {@link DocumentRetriever}：每次从向量库拉取文档后打一条日志，便于确认本轮对话是否真正用到知识库检索结果。
 */
@Slf4j
@RequiredArgsConstructor
public class LoggingDocumentRetriever implements DocumentRetriever {

    private final DocumentRetriever delegate;

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> docs = delegate.retrieve(query);
        int n = docs == null ? 0 : docs.size();
        String q = query != null && query.text() != null ? query.text() : "";
        if (n == 0) {
            log.info(">>>> [RAG-Knowledge] 本轮检索未命中向量片段（query 摘要前 120 字）：{}", abbreviate(q, 120));
        } else {
            log.info(">>>> [RAG-Knowledge] 本轮检索命中 {} 条知识库文档，将注入上下文（query 摘要前 120 字）：{}", n, abbreviate(q, 120));
        }
        return docs;
    }

    private static String abbreviate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
