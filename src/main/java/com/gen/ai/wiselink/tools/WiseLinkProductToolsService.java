package com.gen.ai.wiselink.tools;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.gen.ai.common.exception.RateLimitException;
import com.gen.ai.infrastructure.cache.SearchCacheService;
import com.gen.ai.infrastructure.guard.WiseLinkSearchGuard;
import com.gen.ai.infrastructure.search.UnifiedSearchClient;
import com.gen.ai.infrastructure.search.UnifiedSearchResult;
import com.gen.ai.wiselink.annotation.WiseLinkTool;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 5.16 商品数据唯一入口：{@link #getProductRealtimeStatus}（粮仓
 * {@link SearchCacheService} 优先命中 → 未命中再经 {@link WiseLinkSearchGuard} 限流 →
 * 矛 {@link UnifiedSearchClient}）。
 * <p>
 * 参数提取为扁平逻辑（无深层递归），解析异常在入口 try-catch，避免拖垮对话引擎。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WiseLinkProductToolsService {

    /** 权重最高：仅浅层取值，不向下钻嵌套 Map / 多层集合。 */
    private static final String[] CORE_QUERY_KEYS = { "productName", "q", "item", "keyword" };

    /** 暴力第一层 value 扫描时跳过，避免把用户标识当成检索词。 */
    private static final Set<String> BRUTE_SKIP_KEYS =
            Set.of("userId", "sessionId", "conversationId", "chatId");

    private static final Set<String> CORE_QUERY_KEY_SET =
            Set.of("productName", "q", "item", "keyword");


    private static final String PROVENANCE_CACHE = "Redis 历史检索快照（命中缓存，未消耗 Tavily/Serper 额度）";
    private static final String PROVENANCE_INTENT_SKIP = "策略拦截（意图未命中，未调用外部检索）";
    private static final String PROVENANCE_PARAM = "未发起检索（参数不完整）";
    private static final String PROVENANCE_DEGRADED = "系统兜底（链路异常后的安全占位，非实时网页摘要）";

    /** 从检索正文中抓取一条价格线索，供 Observation 顶栏展示（浅层正则，非结构化解析）。 */
    private static final Pattern PRICE_CUE_PATTERN =
            Pattern.compile("(¥|￥|\\$)\\s*\\d+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?\\s*(?:元|块)(?:钱|人民币)?");

    private final WiseLinkSearchGuard guard;
    private final SearchCacheService searchCacheService;
    private final UnifiedSearchClient unifiedSearchClient;

    /**
     * 全网导购检索：参数洗白 → 意图 → 缓存优先；未命中再限流 → Tavily/Serper/快照 → 写缓存。
     * 全链路单 try-catch：任意未预期异常降级为 Observation，不中断对话。
     */
    @WiseLinkTool(name = "getProductRealtimeStatus", vipOnly = true, description = "【全能商品数据入口】全网实时/缓存导购检索（价格与口碑线索来自公开网页摘要；Tavily→Serper→本地快照）。"
            + " 参数：JSON Map，优先 productName、q、item、keyword；可选 userId。"
            + " 返回带「数据来源」标识的 Markdown，供模型归因与体面报告。勿再使用已移除的独立价格/库存工具名。")
    public String getProductRealtimeStatus(Map<String, Object> params, ToolContext toolContext) {
        try {
            Map<String, Object> safeParams = (params == null) ? Map.of() : params;
            // a. 参数洗白（解析保护：提取异常不向上穿透）
            String q;
            try {
                q = resolveQuery(safeParams, toolContext);
            } catch (Exception parseEx) {
                log.warn(">>>> [WiseLink-Product] 参数提取异常，已跳过本次解析分支: {}", parseEx.toString());
                q = safeContextFallbackQuery(toolContext);
            }
            if (StringUtils.hasText(q)) {
                log.info(">>>> [Param-Recovered] 提取结果: {}", q.strip());
            }
            if (!StringUtils.hasText(q)) {
                return observationWithProvenance(
                        PROVENANCE_PARAM, "缺少有效检索词。", "请提供 productName、query、q 等非空字段。");
            }
            String normalizedQ = q.strip();
            boolean shouldTriggerSearch = guard.shouldTriggerSearch(normalizedQ);
            // b. 意图拦截
            if (!shouldTriggerSearch) {
                return observationWithProvenance(
                        PROVENANCE_INTENT_SKIP,
                        "当前话术未触发实时比价/导购检索（未消耗配额）。",
                        "若需检索，请在问题中包含价格、多少钱、销量、京东等相关表述。");
            }

            String rawSession = WiseLinkToolSecurityInterceptor.extractSessionId(toolContext);
            String sessionId = StringUtils.hasText(rawSession) ? rawSession.strip() : "default";
            String userId = StringUtils.hasText(stringParam(safeParams.get("userId")))
                    ? stringParam(safeParams.get("userId")).strip()
                    : sessionId;

            // c. 缓存检索（命中则不消耗会话限流额度，直接返回）
            String cachedBody = searchCacheService.getCache(normalizedQ);
            if (StringUtils.hasText(cachedBody)) {
                log.info(">>>> [WiseLink-Product] 检索缓存命中 querySnippet={}", abbreviate(normalizedQ));
                return formatCachedRealtimeObservation(normalizedQ, cachedBody);
            }

            // d. 流量管控（仅缓存未命中时消耗配额）
            try {
                guard.checkRateLimit(sessionId, userId);
            } catch (RateLimitException ex) {
                log.warn(">>>> [WiseLink-Product] 限流 sessionId={} userId={}", sessionId, userId);
                return rateLimitedObservation();
            }

            // e. 物理点火
            log.info(
                    ">>>> [WiseLink-Final-Check] q=[{}], trigger=[{}], limitChecked=ok",
                    normalizedQ,
                    shouldTriggerSearch);
            UnifiedSearchResult result = unifiedSearchClient.search(normalizedQ);
            String rendered = formatRealtimeObservation(normalizedQ, result);

            log.info(">>>> [WiseLink-Success] 成功带回数据，即将存入 Redis 缓存");
            // f. 成果入库（写缓存失败不影响返回）
            try {
                searchCacheService.putCache(normalizedQ, result.markdownBody());
            } catch (Exception cacheEx) {
                log.warn(">>>> [WiseLink-Product] putCache 失败: {}", cacheEx.toString());
            }
            return rendered;
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-Product] getProductRealtimeStatus 未预期异常，降级 Observation: {}", ex.toString(), ex);
            return observationWithProvenance(
                    PROVENANCE_DEGRADED,
                    "检索链路出现异常，已返回占位说明以便对话继续。",
                    "异常类型：" + ex.getClass().getSimpleName() + "。");
        }
    }

    private static String rateLimitedObservation() {
        return WiseLinkSearchGuard.toolRateLimitObservationMarkdown();
    }

    private static String observationWithProvenance(String dataProvenance, String headline, String detail) {
        return """
                ### Observation

                - **数据来源**：%s

                %s

                %s
                """
                .formatted(dataProvenance, headline, detail)
                .strip();
    }

    private static String formatRealtimeObservation(String query, UnifiedSearchResult result) {
        String mode = result.snapshotFallback()
                ? "本地快照 / 历史预测（非实时网页抓取）"
                : "全网实时检索（云端 API）";
        String body = result.markdownBody() == null ? "" : result.markdownBody();
        StringBuilder md = new StringBuilder(2048);
        md.append("### Observation\n\n");
        md.append(observationAnalysisModeHeader());
        md.append(coreComparisonDataBanner(query, body));
        md.append("- **数据来源**：").append(result.dataProvenance()).append("\n");
        md.append("- **检索形态**：").append(mode).append("\n\n");
        md.append("## 实时导购快照：").append(escapeMd(query)).append("\n\n");
        md.append(body);
        return md.toString().strip();
    }

    private static String formatCachedRealtimeObservation(String query, String cachedMarkdownBody) {
        String body = cachedMarkdownBody == null ? "" : cachedMarkdownBody;
        StringBuilder md = new StringBuilder(2048);
        md.append("### Observation\n\n");
        md.append(observationAnalysisModeHeader());
        md.append(coreComparisonDataBanner(query, body));
        md.append("- **数据来源**：").append(PROVENANCE_CACHE).append("\n\n");
        md.append("[来自快照]\n\n");
        md.append("## 实时导购快照：").append(escapeMd(query)).append("\n\n");
        md.append(body);
        return md.toString().strip();
    }

    /** Manus/7B：把模型从「再搜一轮」拽到「分析 / 结案」。 */
    private static String observationAnalysisModeHeader() {
        return "# [核心数据已获取，请立即进行比价分析]"
                + System.lineSeparator()
                + System.lineSeparator();
    }

    /**
     * 顶栏强提示，便于 7B 模型识别「数据已齐、应收官」；价格线索来自正文浅层扫描，无则写「见下方」。
     */
    private static String coreComparisonDataBanner(String query, String markdownBody) {
        String name = escapeMd(query);
        String priceCue = extractPriceCueSnippet(markdownBody);
        String pricePart =
                StringUtils.hasText(priceCue)
                        ? "价格线索:" + escapeMd(priceCue)
                        : "价格线索:见下方摘要";
        return "## ⭐ [核心比价数据已送达] 商品名:"
                + name
                + "，"
                + pricePart
                + System.lineSeparator()
                + System.lineSeparator();
    }

    private static String extractPriceCueSnippet(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        Matcher m = PRICE_CUE_PATTERN.matcher(text);
        if (!m.find()) {
            return "";
        }
        int start = Math.max(0, m.start() - 8);
        int end = Math.min(text.length(), Math.max(m.end(), m.start()) + 40);
        String raw = text.substring(start, end).replaceAll("\\s+", " ").strip();
        return raw.length() > 120 ? raw.substring(0, 117) + "…" : raw;
    }

    private static String abbreviate(String s) {
        if (s == null || s.length() <= 64) {
            return s == null ? "" : s;
        }
        return s.substring(0, 61) + "…";
    }

    private static String resolveQuery(Map<String, Object> params, ToolContext toolContext) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        for (String key : CORE_QUERY_KEYS) {
            String fragment = shallowQueryFromValue(safe.get(key));
            if (StringUtils.hasText(fragment)) {
                return fragment.strip();
            }
        }
        for (Map.Entry<String, Object> e : safe.entrySet()) {
            String k = e.getKey();
            if (BRUTE_SKIP_KEYS.contains(k) || CORE_QUERY_KEY_SET.contains(k)) {
                continue;
            }
            Object val = e.getValue();
            if (val instanceof String s && acceptableParamQueryText(s)) {
                return s.strip();
            }
        }
        return safeContextFallbackQuery(toolContext);
    }

    private static String safeContextFallbackQuery(ToolContext toolContext) {
        String last = WiseLinkToolSecurityInterceptor.extractUserMessage(toolContext);
        if (acceptableContextFallbackQueryText(last)) {
            return last.strip();
        }
        return "";
    }

    /** 自 Map 值扁平提取：仅 String / Number / 集合或数组的「首个」标量元素；不遍历嵌套结构。 */
    private static String shallowQueryFromValue(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return acceptableParamQueryText(s) ? s.strip() : "";
        }
        if (v instanceof Number) {
            String s = Objects.toString(v, "").strip();
            return acceptableParamQueryText(s) ? s : "";
        }
        if (v instanceof Boolean) {
            return "";
        }
        if (v instanceof Iterable<?> it) {
            Iterator<?> iter = it.iterator();
            if (!iter.hasNext()) {
                return "";
            }
            return shallowScalarQueryString(iter.next());
        }
        if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            if (len == 0) {
                return "";
            }
            return shallowScalarQueryString(Array.get(v, 0));
        }
        if (v instanceof Map<?, ?>) {
            return "";
        }
        String s = Objects.toString(v, "").strip();
        return acceptableParamQueryText(s) ? s : "";
    }

    private static String shallowScalarQueryString(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof String s) {
            return acceptableParamQueryText(s) ? s.strip() : "";
        }
        if (o instanceof Number) {
            String s = Objects.toString(o, "").strip();
            return acceptableParamQueryText(s) ? s : "";
        }
        return "";
    }

    /** 来自 JSON 字段：长度须大于 2，过滤 "1"、空串及过短噪声。 */
    private static boolean acceptableParamQueryText(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        return s.strip().length() > 2;
    }

    /** 上下文兜底：允许双字用户原话，仍排除空与单字符噪声。 */
    private static boolean acceptableContextFallbackQueryText(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        String t = s.strip();
        return t.length() >= 2;
    }

    private static String stringParam(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s.strip();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return Objects.toString(v).strip();
        }
        return Objects.toString(v, "").strip();
    }

    private static String escapeMd(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }
}
