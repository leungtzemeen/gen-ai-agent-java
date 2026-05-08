package com.gen.ai.infrastructure.search;

/**
 * {@link UnifiedSearchClient#search(String)} 的统一返回：兜底标记 + Markdown 正文 + 给模型看的来源说明。
 */
public record UnifiedSearchResult(boolean snapshotFallback, String markdownBody, String dataProvenance) {}
