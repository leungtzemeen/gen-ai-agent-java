package com.gen.ai.wiselink.tools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gen.ai.wiselink.annotation.WiseLinkTool;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 外部检索：模拟联网低价检索；返回结构化比价摘要供模型直接使用。
 */
@Service
@Slf4j
public class WiseLinkExternalSearchService {

    private static final String MOCK_LINK_BASE = "https://demo.wiselink.invalid/low-price";

    /**
     * 工具入参：支持标准对象 {@code {"query":"关键词"}}，亦兼容模型误传的根级 JSON 字符串（plain string）。
     */
    public record WebSearchRequest(
            @JsonProperty("query") String query) {

        public WebSearchRequest {
            query = query == null ? "" : query.strip();
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static WebSearchRequest fromPlainString(String raw) {
            return new WebSearchRequest(raw == null ? "" : raw.strip());
        }
    }

    @WiseLinkTool(name = "searchProductOnWeb", vipOnly = true, description = "全网比价。参数：JSON 对象 {\"query\":\"无线耳机\"}。严禁裸文本，必须严格遵循 JSON 格式。")
    public String searchProductOnWeb(WebSearchRequest request) {
        try {
            String q = request == null || request.query() == null ? "" : request.query().trim();
            if (q.isEmpty()) {
                return "错误：需要非空的 query。";
            }
            log.info(">>>> [WiseLink-External] 模拟联网搜索 query='{}'", q);

            String enc = URLEncoder.encode(q, StandardCharsets.UTF_8);
            String linkA = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-a&tag=入门";
            String linkB = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-b&tag=热销";
            String linkC = MOCK_LINK_BASE + "?kw=" + enc + "&merchant=demo-c&tag=旗舰";

            return """
                    | 商品 | 价格 | 平台 | 链接 |
                    | --- | --- | --- | --- |
                    | %s | ¥1999–¥2299 | 入门套装 · 商户A | %s |
                    | %s | ¥2088 起 | 热销款 · 商户B | %s |
                    | %s | ¥2149 起 | 旗舰款 · 商户C | %s |
                    """
                    .formatted(q, linkA, q, linkB, q, linkC);
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-External] 模拟搜索异常: {}", ex.toString());
            return "错误：" + ex.getMessage();
        }
    }
}
