package com.gen.ai.infrastructure.rag.ingestion;

import java.util.ArrayList;
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
 * JSON 知识灌库策略：按 {@code goods_id + update_time} 在向量库中判断是否已存在相同版本，未变更则跳过；
 * 有变更则先按 {@code goods_id} 删除旧向量再 {@code accept} 新文档。（{@code create_time}/{@code insert_time} 仅作 metadata，不参与判重。）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JsonKnowledgeIngestionStrategy implements KnowledgeIngestionStrategy {

    private final RagIngestionSidecar ingestionSidecar;

    @Override
    public String supportType() {
        return "json";
    }

    /**
     * 遍历候选文档，过滤缺主键项，对需更新的 {@code goods_id} 批量删旧后写入新文档。
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

        List<Document> toAccept = new ArrayList<>();
        Set<String> goodsIdsToReplace = new HashSet<>();

        for (Document doc : candidates) {
            String goodsId = meta(doc, RagDocumentMetadata.GOODS_ID);
            String updateTime = meta(doc, RagDocumentMetadata.UPDATE_TIME);

            if (goodsId.isEmpty()) {
                log.warn(">>>> [RAG-Ingest] JSON：跳过缺少 goods_id 的文档");
                continue;
            }
            if (updateTime.isEmpty()) {
                log.warn(">>>> [RAG-Ingest] JSON：goods_id={} 缺少 update_time，将按新数据灌库（建议 JSON 补全字段）", goodsId);
            }

            if (duplicateChecker.hasSameGoodsIdAndUpdateTime(goodsId, updateTime)) {
                continue;
            }

            goodsIdsToReplace.add(goodsId);
            toAccept.add(doc);
        }

        if (toAccept.isEmpty()) {
            log.info(">>>> [RAG-Ingest] JSON：本次无待灌库文档（均已存在相同 goods_id+update_time）。");
            return false;
        }

        for (String goodsId : goodsIdsToReplace) {
            ingestionSidecar.removeJsonGoods(goodsId);
            Filter.Expression exp = new FilterExpressionBuilder().eq(RagDocumentMetadata.GOODS_ID, goodsId).build();
            vectorStore.delete(Objects.requireNonNull(exp));
        }

        vectorStore.accept(toAccept);
        for (Document doc : toAccept) {
            ingestionSidecar.putJsonGoods(meta(doc, RagDocumentMetadata.GOODS_ID), meta(doc, RagDocumentMetadata.UPDATE_TIME));
        }
        ingestionSidecar.persistAfterMutation();
        log.info(">>>> [RAG-Ingest] JSON：已按 goods_id 换源灌入 {} 条文档。", toAccept.size());
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
