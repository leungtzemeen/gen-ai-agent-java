package com.gen.ai.infrastructure.mock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.config.StorageProperties;
import com.gen.ai.domain.shopping.ProductProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 演示用商品目录：首次访问时通过 {@link ProductProvider} 按默认类目拉取商品并缓存；
 * 支持按名称/描述关键词与价格区间过滤，结果条数上限由 {@code app.storage.product-query-max-results} 配置。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MockOrderService {

    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    /** 如 2500-4000元、1000 - 1500 元 */
    private static final Pattern BUDGET_SPAN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)");
    /** 如 4000元以上 */
    private static final Pattern BUDGET_ABOVE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元以上");
    private static final BigDecimal PRICE_CEILING = new BigDecimal("999999999");

    /** 输出给前端/模型时剔除的时间相关字段（JSON snake / camel）。 */
    private static final Set<String> TIME_FIELD_KEYS = Set.of(
            "create_time", "insert_time", "update_time",
            "createTime", "insertTime", "updateTime");

    private final ProductProvider productProvider;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    private final Object cacheLock = new Object();
    private volatile List<CachedRow> productCache;

    /**
     * 按可选条件查询商品，返回 JSON 数组字符串（已剔除时间字段，最多 {@link #maxResults()} 条）。
     *
     * @param keyword   可选；非空时在商品名称、简报、预算区间、价格文案、核心规格中做包含匹配（归一化后）
     * @param minPrice  可选；与 {@code maxPrice} 限定用户预算区间。能解析出正参考价（{@code price_info}/{@code price}）时以参考价是否落在区间内为准；否则回退为与 {@code budget_range} 区间求交集。
     * @param maxPrice  可选；同上
     */
    public String searchProductsAsJson(String keyword, String minPrice, String maxPrice) {
        BigDecimal min = parseOptionalBigDecimal(minPrice);
        BigDecimal max = parseOptionalBigDecimal(maxPrice);
        String kw = keyword == null ? "" : keyword.strip();
        int limit = maxResults();

        List<Map<String, Object>> matched = new ArrayList<>();

        if (kw.isEmpty() && min == null && max == null) {
            List<CachedRow> all = snapshot();
            for (int i = 0; i < Math.min(limit, all.size()); i++) {
                matched.add(all.get(i).payload());
            }
        } else {
            for (CachedRow row : snapshot()) {
                if (!passesPriceFilter(row, min, max)) {
                    continue;
                }
                if (!kw.isEmpty() && !matchesKeyword(row, kw)) {
                    continue;
                }
                matched.add(row.payload());
                if (matched.size() >= limit) {
                    break;
                }
            }
        }

        try {
            String json = objectMapper.writeValueAsString(matched);
            log.info(">>>> [MOCK-DB] 商品查询 keyword='{}' min={} max={} -> {} 条（上限 {}）", kw, min, max, matched.size(), limit);
            return json;
        } catch (JsonProcessingException e) {
            log.error(">>>> [MOCK-DB] 商品列表序列化失败", e);
            return "[]";
        }
    }

    private int maxResults() {
        int n = storageProperties.getStorage().getProductQueryMaxResults();
        return n > 0 ? n : 5;
    }

    private List<CachedRow> snapshot() {
        if (productCache != null) {
            return productCache;
        }
        synchronized (cacheLock) {
            if (productCache == null) {
                productCache = Collections.unmodifiableList(loadFromProvider());
            }
            return productCache;
        }
    }

    private List<CachedRow> loadFromProvider() {
        String category = storageProperties.getKnowledge().getDefaultBizCategory();
        List<Map<String, Object>> rows = productProvider.selectByCategory(category);
        if (rows == null || rows.isEmpty()) {
            log.warn(">>>> [MOCK-DB] ProductProvider 未返回商品，类目='{}'", category);
            return List.of();
        }
        List<CachedRow> out = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> sanitized = sanitizeForOutput(row);
            String id = String.valueOf(sanitized.getOrDefault("goods_id", sanitized.get("goodsId")));
            if (!seenIds.add(id)) {
                continue;
            }
            BigDecimal price = parsePrice(row.get("price_info"));
            if (price.signum() == 0) {
                price = parsePrice(row.get("price"));
            }
            BudgetInterval budget = parseBudgetInterval(
                    textOfRaw(row.get("budget_range"), row.get("budgetRange")));
            out.add(new CachedRow(sanitized, price, budget));
        }
        log.info(">>>> [MOCK-DB] 已从 ProductProvider 加载 {} 条商品（类目='{}'）并缓存", out.size(), category);
        return out;
    }

    /**
     * 浅拷贝并去掉时间字段；{@code core_specs} 等集合做一层拷贝，避免外部修改缓存。
     */
    private static Map<String, Object> sanitizeForOutput(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (TIME_FIELD_KEYS.contains(e.getKey())) {
                continue;
            }
            copy.put(e.getKey(), copyValue(e.getValue()));
        }
        return copy;
    }

    private static Object copyValue(Object v) {
        if (v instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>(m);
        }
        return v;
    }

    private static BigDecimal parsePrice(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        Matcher m = FIRST_NUMBER.matcher(String.valueOf(raw));
        if (!m.find()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(m.group(1));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal parseOptionalBigDecimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 价格条件：有正参考价时按参考价是否落在用户区间内判断（与「标价在预算内」一致）；参考价缺失时再与 {@code budget_range} 求交集。
     * <p>
     * 若仍优先用档位与用户区间相交，会把「1999 元起」但档位写成宽区间（如与 2500–3500 相交）的商品误选进来。
     */
    private static boolean passesPriceFilter(CachedRow row, BigDecimal qMin, BigDecimal qMax) {
        if (qMin == null && qMax == null) {
            return true;
        }
        BigDecimal ref = row.numericPrice();
        if (ref != null && ref.signum() > 0) {
            if (qMin != null && ref.compareTo(qMin) < 0) {
                return false;
            }
            if (qMax != null && ref.compareTo(qMax) > 0) {
                return false;
            }
            return true;
        }
        BudgetInterval bi = row.budgetInterval();
        if (bi != null && bi.hasBounds()) {
            return intervalsOverlap(qMin, qMax, bi.low(), bi.high());
        }
        return false;
    }

    /** 闭区间 [a,b] 与 [c,d] 相交（任一端 null 表示无界，用极大/极小代替）。 */
    private static boolean intervalsOverlap(BigDecimal qMin, BigDecimal qMax, BigDecimal bLow, BigDecimal bHigh) {
        BigDecimal loQ = qMin != null ? qMin : BigDecimal.ZERO;
        BigDecimal hiQ = qMax != null ? qMax : PRICE_CEILING;
        BigDecimal loB = bLow != null ? bLow : BigDecimal.ZERO;
        BigDecimal hiB = bHigh != null ? bHigh : PRICE_CEILING;
        return loQ.compareTo(hiB) <= 0 && loB.compareTo(hiQ) <= 0;
    }

    /**
     * 从 budget_range 解析档位区间；识别「a-b元」「a元以上」等。
     */
    private static BudgetInterval parseBudgetInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        Matcher span = BUDGET_SPAN.matcher(s);
        if (span.find()) {
            try {
                BigDecimal low = new BigDecimal(span.group(1));
                BigDecimal high = new BigDecimal(span.group(2));
                return new BudgetInterval(low, high);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher above = BUDGET_ABOVE.matcher(s);
        if (above.find()) {
            try {
                BigDecimal low = new BigDecimal(above.group(1));
                return new BudgetInterval(low, null);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String textOfRaw(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank()) {
            return String.valueOf(a);
        }
        if (b != null) {
            return String.valueOf(b);
        }
        return "";
    }

    private boolean matchesKeyword(CachedRow row, String keywordRaw) {
        String normKw = normalize(keywordRaw);
        if (normKw.isEmpty()) {
            return true;
        }
        Map<String, Object> p = row.payload();
        String name = textOf(p.get("product_name"), p.get("productName"));
        String brief = textOf(p.get("brief_review"), p.get("briefReview"));
        String budget = textOf(p.get("budget_range"), p.get("budgetRange"));
        String priceInfo = textOf(p.get("price_info"), p.get("priceInfo"));
        String specs = String.valueOf(p.getOrDefault("core_specs", ""));
        String bizCat = textOf(p.get("biz_category"), p.get("bizCategory"));

        String blob = normalize(name + brief + budget + priceInfo + specs + bizCat);
        return blob.contains(normKw);
    }

    private static String textOf(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank()) {
            return String.valueOf(a);
        }
        if (b != null) {
            return String.valueOf(b);
        }
        return "";
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record CachedRow(Map<String, Object> payload, BigDecimal numericPrice, BudgetInterval budgetInterval) {
        CachedRow {
            Objects.requireNonNull(payload);
            Objects.requireNonNull(numericPrice);
        }
    }

    /** 商品档位区间；{@code high == null} 表示「元以上」无上界。 */
    private record BudgetInterval(BigDecimal low, BigDecimal high) {
        boolean hasBounds() {
            return low != null || high != null;
        }
    }
}
