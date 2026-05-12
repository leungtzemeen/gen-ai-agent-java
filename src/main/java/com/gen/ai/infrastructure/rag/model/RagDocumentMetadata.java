package com.gen.ai.infrastructure.rag.model;

/**
 * 向量库 {@link org.springframework.ai.document.Document} 的 metadata 键名约定，供各 {@code Extractor} 与灌库策略共用，避免魔法字符串分散。
 */
public final class RagDocumentMetadata {

    /** Markdown 来源文件名或 JSON 资源文件名，用于按来源删旧、去重。 */
    public static final String SOURCE = "source";
    /** Markdown 文件内容 MD5，用于判断文件是否变更。 */
    public static final String FILE_HASH = "file_hash";
    /** 商品等业务主键，JSON/MySQL 行级灌库与删旧依据。 */
    public static final String GOODS_ID = "goods_id";
    /** 业务更新时间，与 {@link #GOODS_ID} 共同用于 JSON 增量判重。 */
    public static final String UPDATE_TIME = "update_time";
    /** 创建时间（来自 JSON 等数据源，供检索侧展示或过滤，灌库判重仍以 {@link #UPDATE_TIME} 为准）。 */
    public static final String CREATE_TIME = "create_time";
    /** 新增/入库时间（来自 JSON 等数据源，语义见数据目录下 README）。 */
    public static final String INSERT_TIME = "insert_time";
    /** 业务分类，用于检索过滤与 JSON 行过滤。 */
    public static final String BIZ_CATEGORY = "biz_category";

    private RagDocumentMetadata() {}
}
