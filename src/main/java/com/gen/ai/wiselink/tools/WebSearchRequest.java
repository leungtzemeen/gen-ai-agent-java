package com.gen.ai.wiselink.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 历史工具 {@code searchProductOnWeb} 的入参模型；5.13 后仅保留供 JSON 反序列化测试与文档兼容。
 */
public record WebSearchRequest(@JsonProperty("query") String query) {

    public WebSearchRequest {
        query = query == null ? "" : query.strip();
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static WebSearchRequest fromPlainString(String raw) {
        return new WebSearchRequest(raw == null ? "" : raw.strip());
    }
}
