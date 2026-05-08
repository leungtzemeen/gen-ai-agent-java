package com.gen.ai.infrastructure.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一联网检索：Tavily → Serper 降级 → 本地快照兜底（无视 Selenium，纯 HTTP）。
 */
@Component
@Slf4j
public class UnifiedSearchClient {

    /** 云端 API 成功时给 AI 的来源标识 */
    public static final String PROVENANCE_LIVE_WEB = "全网实时检索（Tavily / Serper 等搜索引擎 API 聚合）";

    /** 本地演示快照，非实时 */
    public static final String PROVENANCE_LOCAL_SNAPSHOT = "本地快照 · 历史预测（非实时，用于额度、403 或网络异常时的兜底）";

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final String SERPER_URL = "https://google.serper.dev/search";
    private static final int MAX_ROWS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    @Value("${app.search.tavily-key:${TAVILY_API_KEY:}}")
    private String tavilyApiKey;

    @Value("${app.search.serper-key:${SERPER_API_KEY:}}")
    private String serperApiKey;

    public UnifiedSearchClient(RestClient.Builder restClientBuilder) {
        this.http = restClientBuilder.build();
    }

    /**
     * 全链路不因单次 HTTP 403/异常而向上抛：内部降级 Serper → 本地快照；最外层再兜一层异常。
     *
     * @param query 检索词
     */
    public UnifiedSearchResult search(String query) {
        if (query == null || !StringUtils.hasText(query.strip())) {
            return new UnifiedSearchResult(true, mockSnapshotMarkdown(""), PROVENANCE_LOCAL_SNAPSHOT);
        }
        String q = query.strip();
        try {
            if (StringUtils.hasText(tavilyApiKey)) {
                try {
                    String body =
                            http.post()
                                    .uri(TAVILY_URL)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(
                                            Map.of(
                                                    "api_key", tavilyApiKey.strip(),
                                                    "query", q,
                                                    "max_results", MAX_ROWS))
                                    .retrieve()
                                    .body(String.class);
                    return new UnifiedSearchResult(false, formatFromTavilyJson(body, q), PROVENANCE_LIVE_WEB);
                } catch (RestClientException ex) {
                    log.warn(">>>> [UnifiedSearch] Tavily 不可用，尝试 Serper: {}", ex.toString());
                } catch (Exception ex) {
                    log.warn(">>>> [UnifiedSearch] Tavily 解析失败，尝试 Serper: {}", ex.toString());
                }
            } else {
                log.debug(">>>> [UnifiedSearch] 未配置 TAVILY_API_KEY，跳过 Tavily");
            }

            if (StringUtils.hasText(serperApiKey)) {
                try {
                    String body =
                            http.post()
                                    .uri(SERPER_URL)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("X-API-KEY", serperApiKey.strip())
                                    .body(Map.of("q", q, "num", MAX_ROWS))
                                    .retrieve()
                                    .body(String.class);
                    return new UnifiedSearchResult(false, formatFromSerperJson(body, q), PROVENANCE_LIVE_WEB);
                } catch (Exception ex) {
                    log.warn(">>>> [UnifiedSearch] Serper 不可用，使用快照兜底: {}", ex.toString());
                }
            } else {
                log.debug(">>>> [UnifiedSearch] 未配置 SERPER_API_KEY，跳过 Serper");
            }

            return new UnifiedSearchResult(true, mockSnapshotMarkdown(q), PROVENANCE_LOCAL_SNAPSHOT);
        } catch (Exception ex) {
            log.warn(">>>> [UnifiedSearch] 未预期异常，强制本地快照: {}", ex.toString());
            return new UnifiedSearchResult(true, mockSnapshotMarkdown(q), PROVENANCE_LOCAL_SNAPSHOT);
        }
    }

    private static String formatFromTavilyJson(String json, String queryLabel) throws Exception {
        JsonNode root = JSON.readTree(json);
        JsonNode results = root.get("results");
        List<Map<String, String>> rows = new ArrayList<>();
        if (results != null && results.isArray()) {
            int i = 0;
            for (JsonNode r : results) {
                if (i >= MAX_ROWS) {
                    break;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("title", text(r, "title"));
                row.put("snippet", text(r, "content"));
                row.put("url", text(r, "url"));
                rows.add(row);
                i++;
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("Tavily 返回空结果");
        }
        return renderMarkdownTable(queryLabel, rows, "Tavily");
    }

    private static String formatFromSerperJson(String json, String queryLabel) throws Exception {
        JsonNode root = JSON.readTree(json);
        JsonNode organic = root.get("organic");
        List<Map<String, String>> rows = new ArrayList<>();
        if (organic != null && organic.isArray()) {
            int i = 0;
            for (JsonNode r : organic) {
                if (i >= MAX_ROWS) {
                    break;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("title", text(r, "title"));
                row.put("snippet", text(r, "snippet"));
                row.put("url", text(r, "link"));
                rows.add(row);
                i++;
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("Serper 返回无 organic 结果");
        }
        return renderMarkdownTable(queryLabel, rows, "Serper");
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node == null ? null : node.get(field);
        return n == null || n.isNull() ? "" : n.asText("").strip();
    }

    private static String renderMarkdownTable(String queryLabel, List<Map<String, String>> rows, String engine) {
        StringBuilder md = new StringBuilder(1024);
        md.append("- 引擎：").append(engine).append("\n");
        md.append("- 关键词：").append(queryLabel).append("\n\n");
        md.append("| # | 标题 | 摘要 | 链接 |\n");
        md.append("| --- | --- | --- | --- |\n");
        int n = 1;
        for (Map<String, String> r : rows) {
            md.append("| ")
                    .append(n++)
                    .append(" | ")
                    .append(escapeMd(r.getOrDefault("title", "")))
                    .append(" | ")
                    .append(escapeMd(trimSnippet(r.getOrDefault("snippet", ""))))
                    .append(" | ")
                    .append(linkCell(r.getOrDefault("url", "")))
                    .append(" |\n");
        }
        return md.toString().strip();
    }

    private static String trimSnippet(String s) {
        if (s.length() <= 240) {
            return s;
        }
        return s.substring(0, 237) + "…";
    }

    private static String linkCell(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        return "[" + "打开" + "](" + url + ")";
    }

    private static String escapeMd(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    private static String mockSnapshotMarkdown(String query) {
        String enc = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        String u1 = "https://demo.wiselink.invalid/snapshot?kw=" + enc + "&src=mock-a";
        String u2 = "https://demo.wiselink.invalid/snapshot?kw=" + enc + "&src=mock-b";
        return """
                ### Observation

                由于流量限制，当前提供云端快照数据

                ## 快照条目（演示）

                | # | 标题 | 摘要 | 链接 |
                | --- | --- | --- | --- |
                | 1 | %s · 入门参考款 | 云端快照：典型价位区间与参数摘要（非实时）。 | [打开](%s) |
                | 2 | %s · 热销参考款 | 云端快照：销量与口碑摘要（非实时）。 | [打开](%s) |
                """
                .formatted(query, u1, query, u2)
                .strip();
    }
}
