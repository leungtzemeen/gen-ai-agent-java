package com.gen.ai.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 检索后熔断：单条切片超过指定字符数时物理截断，避免单文档撑爆 Prompt。
 */
@Component
public class RagDocumentTruncatePostProcessor implements DocumentPostProcessor {

    /** 与架构要求一致：单片段最多 500 字（字符） */
    public static final int MAX_FRAGMENT_CHARS = 500;

    /** 进入 Prompt 的检索切片总数上限（多路 query 合并后再截断） */
    public static final int MAX_DOCUMENTS_IN_CONTEXT = 2;

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        List<Document> out = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            Document t = truncate(doc);
            if (t != null) {
                out.add(t);
            }
        }
        return out.stream().limit(MAX_DOCUMENTS_IN_CONTEXT).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Document truncate(Document doc) {
        if (doc == null) {
            return null;
        }
        String t = doc.getText();
        if (t == null || t.length() <= MAX_FRAGMENT_CHARS) {
            return doc;
        }
        String cut = t.substring(0, MAX_FRAGMENT_CHARS) + "…";
        return Document.builder().text(cut).metadata(new HashMap<>(doc.getMetadata())).build();
    }
}
