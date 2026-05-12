package com.gen.ai.infrastructure.rag.ingestion;

/**
 * 基于向量库已有数据的只读去重探测（自身不写入向量库）。
 * <p>
 * Markdown：{@link com.gen.ai.infrastructure.rag.extractor.MarkdownKnowledgeExtractor} 在解析前调用，跳过未改文件；
 * JSON：{@link JsonKnowledgeIngestionStrategy} 在灌库时调用，跳过相同 {@code goods_id + update_time}。
 */
public interface KnowledgeDuplicateChecker {

    /**
     * 是否已存在相同 {@code source} 且相同 {@code file_hash} 的文档（Markdown 文件级）。
     *
     * @param source   通常文件名
     * @param fileHash 文件内容 MD5
     */
    boolean hasSameSourceAndHash(String source, String fileHash);

    /**
     * 是否已存在相同 {@code goods_id} 且相同 {@code update_time} 的文档（JSON / 未来 MySQL 行级增量）。
     */
    boolean hasSameGoodsIdAndUpdateTime(String goodsId, String updateTime);
}
