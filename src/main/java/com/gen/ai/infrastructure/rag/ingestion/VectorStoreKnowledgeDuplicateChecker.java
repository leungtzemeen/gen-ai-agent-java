package com.gen.ai.infrastructure.rag.ingestion;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 元数据判重：委托 {@link RagIngestionSidecar} 内存台账，避免 {@link org.springframework.ai.vectorstore.VectorStore#similaritySearch}
 * 在判重路径上触发 embedding。
 */
@Component
@RequiredArgsConstructor
public class VectorStoreKnowledgeDuplicateChecker implements KnowledgeDuplicateChecker {

    private final RagIngestionSidecar sidecar;

    @Override
    public boolean hasSameSourceAndHash(String source, String fileHash) {
        return sidecar.hasSameSourceAndHash(source, fileHash);
    }

    @Override
    public boolean hasSameGoodsIdAndUpdateTime(String goodsId, String updateTime) {
        return sidecar.hasSameGoodsIdAndUpdateTime(goodsId, updateTime);
    }
}
