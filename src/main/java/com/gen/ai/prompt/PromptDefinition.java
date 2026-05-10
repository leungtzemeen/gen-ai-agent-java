package com.gen.ai.prompt;

/**
 * 核心资产：全系统所有合法的 Prompt 标签物理收口点
 * 彻底消灭硬编码字符串字面量
 */
public enum PromptDefinition {
    
    /** 历史生成检索词 */
    COMPRESSION_QUERY_TRANSFORM("COMPRESSION_QUERY_TRANSFORM"),
    
    /** 结合内部知识回答 */
    CONTEXTUAL_QUERY_AUGMENT("CONTEXTUAL_QUERY_AUGMENT"),
    
    /** 多查询改写助手 */
    MULTI_QUERY_EXPANDER("MULTI_QUERY_EXPANDER");

    private final String key;

    PromptDefinition(String key) {
        this.key = key;
    }

    public String getKey() {
        return this.key;
    }
}
