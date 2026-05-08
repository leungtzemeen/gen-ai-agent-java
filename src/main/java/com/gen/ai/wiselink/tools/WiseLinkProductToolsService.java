package com.gen.ai.wiselink.tools;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.gen.ai.common.exception.RateLimitException;
import com.gen.ai.infrastructure.nlp.WiseLinkNlpUtil;
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
    private static final String PROVENANCE_FOOTPRINT_SKIP = "实体防抖（本会话已物理检索过，未重复调用外部接口）";
    private static final String PROVENANCE_ATOMIC_STITCH = "多实体原子检索（缓存 / 足迹 / 物理检索缝合）";
    private static final String PROVENANCE_SEMANTIC_FIREWALL = "语义防火墙（抽象词或无效检索 Key，未调用外部检索与缓存）";

    private static final int MULTI_ENTITY_SEARCH_TIMEOUT_SEC = 10;

    /** 纯度：纯数字（无型号字母）不可作为商品检索 Key。 */
    private static final Pattern ENTITY_KEY_PURE_NUMBER = Pattern.compile("^\\d+([.,]\\d+)?$");

    /** 纯度：无字母、无数字、无汉字 → 纯符号噪声。 */
    private static final Pattern ENTITY_KEY_PURE_SYMBOL =
            Pattern.compile("^[^\\p{L}\\p{N}\\p{IsHan}]+$");

    /** 从检索正文中抓取一条价格线索，供 Observation 顶栏展示（浅层正则，非结构化解析）。 */
    private static final Pattern PRICE_CUE_PATTERN =
            Pattern.compile("(¥|￥|\\$)\\s*\\d+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?\\s*(?:元|块)(?:钱|人民币)?");

    /**
     * 上下文回溯时剔除的指令话术（长短语优先整体删除，避免残留碎片）。
     */
    private static final String[] CONTEXT_QUERY_NOISE_PHRASES_ORDERED = {
        "请帮我",
        "查一下",
        "完成之后",
        "完成后",
        "专业版",
        "告诉我",
        "以及",
        "还有",
        "或者",
        "启动",
        "模式",
        "执行",
        "任务",
        "搜索",
        "调研",
        "实时",
        "请问",
        "导购",
        "报告",
        "生成",
        "看看"
    };

    private static final Set<String> CONTEXT_QUERY_NOISE_TOKENS =
            Set.of(
                    "启动",
                    "专业版",
                    "模式",
                    "执行",
                    "任务",
                    "对比",
                    "一下",
                    "查一下",
                    "搜索",
                    "调研",
                    "实时",
                    "告诉我",
                    "请问",
                    "导购",
                    "报告",
                    "生成",
                    "去",
                    "看看",
                    "manus",
                    "和",
                    "与",
                    "及",
                    "还有",
                    "或者",
                    "以及",
                    "完成");

    /** 中英混排商品名：在汉字与字母数字边界插入空格，便于分词。 */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{IsHan}]+|[A-Za-z][A-Za-z0-9]*|\\d+(?:\\.\\d+)?");

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
                q = resolveQueryFromRaw(safeContextFallbackQueryRaw(toolContext));
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

            if (isRedisDirtyAbstractQuery(normalizedQ)) {
                log.info(
                        ">>>> [Semantic-Firewall] 归一化检索词为抽象脏词 [{}]，忽略 Redis 缓存，返回空 Observation",
                        normalizedQ);
                return "";
            }

            String rawSession = WiseLinkToolSecurityInterceptor.extractSessionId(toolContext);
            String sessionId = StringUtils.hasText(rawSession) ? rawSession.strip() : "default";
            String userId = StringUtils.hasText(stringParam(safeParams.get("userId")))
                    ? stringParam(safeParams.get("userId")).strip()
                    : sessionId;

            String rawFragment = resolveRawQueryFragment(safeParams, toolContext);
            List<String> entityAtoms = resolveEntityAtoms(rawFragment);
            if (entityAtoms.isEmpty()) {
                entityAtoms = new ArrayList<>(List.of(normalizedQ));
            }

            String nlpSegmentSource = dehydrateSearchQueryString(rawFragment == null ? "" : rawFragment.strip());
            if (!StringUtils.hasText(nlpSegmentSource)) {
                nlpSegmentSource = normalizedQ;
            }
            log.info(">>>> [NLP-Segment] 原始请求: [{}], 拆解出的原子实体: {}", nlpSegmentSource, entityAtoms);

            log.info(
                    ">>>> [WiseLink-5.21] 原子实体数={} sessionId={} normalizedKeySnippet={}",
                    entityAtoms.size(),
                    sessionId,
                    abbreviate(normalizedQ));

            List<CompletableFuture<EntitySlice>> futures = new ArrayList<>(entityAtoms.size());
            for (String entity : entityAtoms) {
                String atom = entity.strip();
                if (isEntityKeySemanticBlocked(atom)) {
                    log.info(">>>> [Semantic-Firewall] 实体 Key 未通过纯度校验，跳过缓存与物理检索: [{}]", atom);
                    futures.add(CompletableFuture.completedFuture(entitySliceSemanticFirewall(atom)));
                    continue;
                }
                String cachedBody = searchCacheService.getCache(atom);
                if (StringUtils.hasText(cachedBody)) {
                    log.info(">>>> [Cache-Hit] 实体: [{}], 直接从 Redis 读取", atom);
                    futures.add(CompletableFuture.completedFuture(entitySliceFromCache(atom, cachedBody)));
                    continue;
                }
                if (guard.shouldBlockPhysicalSearch(sessionId, atom)) {
                    log.info(">>>> [Footprint-Blocked] 实体: [{}], 命中 Session 拦截", atom);
                    futures.add(CompletableFuture.completedFuture(entitySliceFootprintBlock(atom)));
                    continue;
                }
                try {
                    guard.checkRateLimit(sessionId, userId);
                } catch (RateLimitException ex) {
                    log.warn(">>>> [WiseLink-Product] 实体级限流 sessionId={} entity={}", sessionId, abbreviate(atom));
                    futures.add(CompletableFuture.completedFuture(entitySliceRateLimited(atom)));
                    continue;
                }
                log.info(">>>> [API-Fire] 实体: [{}], 正在发起物理异步搜索...", atom);
                final String physicalEntity = atom;
                futures.add(
                        CompletableFuture.supplyAsync(
                                () -> runPhysicalSearchForEntity(physicalEntity, sessionId)));
            }

            CompletableFuture<?>[] arr = futures.toArray(new CompletableFuture<?>[0]);
            try {
                CompletableFuture.allOf(arr).get(MULTI_ENTITY_SEARCH_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.warn(
                        ">>>> [WiseLink-Product] 多实体检索超时 ({}s)，取消未完成任务",
                        MULTI_ENTITY_SEARCH_TIMEOUT_SEC);
                for (CompletableFuture<EntitySlice> f : futures) {
                    f.cancel(true);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn(">>>> [WiseLink-Product] 多实体检索被中断: {}", ie.toString());
            }

            List<EntitySlice> slices = new ArrayList<>(entityAtoms.size());
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<EntitySlice> f = futures.get(i);
                String labelEntity = entityAtoms.get(i).strip();
                try {
                    slices.add(f.get());
                } catch (CancellationException ce) {
                    slices.add(entitySliceTimeoutOrCancelled(labelEntity));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    slices.add(entitySliceTimeoutOrCancelled(labelEntity));
                } catch (ExecutionException ee) {
                    slices.add(entitySliceFailure(labelEntity, ee.getCause()));
                }
            }

            appendFingerprintFallbackIfNeeded(
                    slices, rawFragment, normalizedQ, entityAtoms, sessionId, userId);

            return formatStitchedMultiEntityObservation(slices, normalizedQ);
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

    private record EntitySlice(String entity, String sourceLabel, String dataProvenance, String innerMarkdown) {}

    private static List<String> resolveEntityAtoms(String rawFragment) {
        if (!StringUtils.hasText(rawFragment)) {
            return List.of();
        }
        String forNlp = dehydrateSearchQueryString(rawFragment.strip());
        if (!StringUtils.hasText(forNlp)) {
            return List.of();
        }
        try {
            List<String> extracted = WiseLinkNlpUtil.extractProductEntities(forNlp);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String e : extracted) {
                if (StringUtils.hasText(e)) {
                    seen.add(e.strip());
                }
            }
            return new ArrayList<>(seen);
        } catch (Throwable t) {
            log.warn(">>>> [WiseLink-Product] 实体原子列表解析失败: {}", t.toString());
            return List.of();
        }
    }

    private static EntitySlice entitySliceFromCache(String entity, String cachedBody) {
        return new EntitySlice(
                entity,
                "Redis 缓存命中",
                PROVENANCE_CACHE,
                cachedBody == null ? "" : cachedBody);
    }

    private static EntitySlice entitySliceFootprintBlock(String entity) {
        return new EntitySlice(
                entity,
                "实体足迹防抖",
                PROVENANCE_FOOTPRINT_SKIP,
                """
                        [数据已在上下文]

                        本会话内已对该实体执行过物理检索；请勿重复探测。请结合此前 Observation 与对话记忆整理结论。"""
                        .strip());
    }

    private static EntitySlice entitySliceRateLimited(String entity) {
        return new EntitySlice(
                entity,
                "会话配额已满（该实体未再调用外部接口）",
                "实时检索配额",
                "本会话检索额度已用尽，**本实体**未再发起外部检索。请基于已有 Observation 结案。");
    }

    private static EntitySlice entitySliceTimeoutOrCancelled(String entity) {
        return new EntitySlice(
                entity,
                "等待超时 / 任务中断",
                PROVENANCE_DEGRADED,
                "[系统] 本实体检索未在 " + MULTI_ENTITY_SEARCH_TIMEOUT_SEC + " 秒内完成或已被取消。");
    }

    private static EntitySlice entitySliceFailure(String entity, Throwable cause) {
        String msg = cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new EntitySlice(
                entity, "物理检索异常", PROVENANCE_DEGRADED, "检索失败：" + escapeMd(msg));
    }

    private static EntitySlice entitySliceSemanticFirewall(String entity) {
        return new EntitySlice(
                entity,
                "语义防火墙",
                PROVENANCE_SEMANTIC_FIREWALL,
                """
                        [语义防火墙]

                        该检索片段为抽象泛词、纯数字/纯符号或过短 Key，未读取 Redis、未发起物理检索。请提供**具体商品型号或品牌+型号**后再试。"""
                        .strip());
    }

    /** 归一化检索词仅为「数据」「信息」时视为 Redis 脏 Key，禁止向模型投喂缓存。 */
    private static boolean isRedisDirtyAbstractQuery(String normalizedQ) {
        if (!StringUtils.hasText(normalizedQ)) {
            return false;
        }
        String t = normalizedQ.strip();
        return "数据".equals(t) || "信息".equals(t);
    }

    /**
     * 5.21 纯度：过短、纯数字、纯符号或命中 NLP 抽象黑名单的实体 Key 禁止点火（亦不读缓存）。
     */
    private static boolean isEntityKeySemanticBlocked(String entityKey) {
        if (!StringUtils.hasText(entityKey)) {
            return true;
        }
        String k = entityKey.strip();
        if (k.length() < 2) {
            return true;
        }
        if (WiseLinkNlpUtil.isAbstractBlacklistToken(k)) {
            return true;
        }
        if (ENTITY_KEY_PURE_NUMBER.matcher(k).matches()) {
            return true;
        }
        return ENTITY_KEY_PURE_SYMBOL.matcher(k).matches();
    }

    private EntitySlice runPhysicalSearchForEntity(String entity, String sessionId) {
        try {
            log.info(">>>> [WiseLink-Final-Check] 物理检索 entity=[{}]", entity);
            UnifiedSearchResult r = unifiedSearchClient.search(entity);
            String body = r.markdownBody() == null ? "" : r.markdownBody();
            try {
                searchCacheService.putCache(entity, body);
            } catch (Exception cacheEx) {
                log.warn(">>>> [WiseLink-Product] putCache 失败 entity={} err={}", abbreviate(entity), cacheEx.toString());
            }
            guard.recordEntityFootprint(sessionId, entity);
            String mode =
                    r.snapshotFallback()
                            ? "本地快照 / 历史预测（非实时网页抓取）"
                            : "全网实时检索（云端 API）";
            String inner = "- **检索形态**：" + mode + "\n\n" + body;
            log.info(">>>> [WiseLink-Success] 实体检索完成 entity={} snapshotFallback={}", abbreviate(entity), r.snapshotFallback());
            return new EntitySlice(entity, "物理检索（UnifiedSearchClient）", r.dataProvenance(), inner);
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-Product] 实体检索异常 entity={} err={}", abbreviate(entity), ex.toString());
            return entitySliceFailure(entity, ex);
        }
    }

    /**
     * 拆分的原子实体正文均未出现价格线索时，用脱水「全词指纹」再搜一次，尽量拉回带比价语义的聚合摘要。
     */
    private void appendFingerprintFallbackIfNeeded(
            List<EntitySlice> slices,
            String rawFragment,
            String normalizedQ,
            List<String> entityAtoms,
            String sessionId,
            String userId) {
        if (slices == null || slices.isEmpty()) {
            return;
        }
        if (!slicesLackPriceCue(slices)) {
            return;
        }
        String fingerprint = dehydrateSearchQueryString(rawFragment == null ? "" : rawFragment.strip());
        if (!StringUtils.hasText(fingerprint)) {
            fingerprint = normalizedQ == null ? "" : normalizedQ.strip();
        }
        if (!StringUtils.hasText(fingerprint)) {
            return;
        }
        if (fingerprintRedundantWithSingleAtomSearch(fingerprint, entityAtoms)) {
            return;
        }
        String fp = fingerprint.strip();
        if (isRedisDirtyAbstractQuery(fp) || isEntityKeySemanticBlocked(fp)) {
            log.info(">>>> [Semantic-Firewall] 全词指纹未通过校验，跳过兜底检索 fp={}", abbreviate(fp));
            return;
        }
        String cached = searchCacheService.getCache(fp);
        if (StringUtils.hasText(cached)) {
            slices.add(
                    new EntitySlice(
                            fp,
                            "Redis 缓存命中（全词指纹）",
                            PROVENANCE_CACHE,
                            cached));
            log.info(">>>> [WiseLink-5.22] 全词指纹缓存命中 fp={}", abbreviate(fp));
            return;
        }
        if (guard.shouldBlockPhysicalSearch(sessionId, fp)) {
            slices.add(entitySliceFootprintBlock(fp));
            log.debug(">>>> [WiseLink-5.22] 全词指纹被足迹拦截 fp={}", abbreviate(fp));
            return;
        }
        try {
            guard.checkRateLimit(sessionId, userId);
        } catch (RateLimitException ex) {
            log.warn(
                    ">>>> [WiseLink-Product] 全词指纹兜底未执行（限流） sessionId={} fp={}",
                    sessionId,
                    abbreviate(fp));
            return;
        }
        EntitySlice physical = runPhysicalSearchForEntity(fp, sessionId);
        slices.add(
                new EntitySlice(
                        physical.entity(),
                        "全词指纹兜底检索（UnifiedSearchClient）",
                        physical.dataProvenance(),
                        physical.innerMarkdown()));
        log.info(">>>> [WiseLink-5.22] 全词指纹物理检索完成 fp={}", abbreviate(fp));
    }

    private static boolean slicesLackPriceCue(List<EntitySlice> slices) {
        return slices.stream().noneMatch(s -> sliceHasPriceCue(s.innerMarkdown()));
    }

    private static boolean sliceHasPriceCue(String markdown) {
        return StringUtils.hasText(extractPriceCueSnippet(markdown));
    }

    private static boolean fingerprintRedundantWithSingleAtomSearch(String fingerprint, List<String> entityAtoms) {
        if (!StringUtils.hasText(fingerprint) || entityAtoms == null || entityAtoms.isEmpty()) {
            return true;
        }
        if (entityAtoms.size() != 1) {
            return false;
        }
        String compactFp = fingerprint.replaceAll("\\s+", "");
        String compactAtom = entityAtoms.get(0).replaceAll("\\s+", "");
        return compactFp.equalsIgnoreCase(compactAtom);
    }

    private static String formatStitchedMultiEntityObservation(List<EntitySlice> slices, String summaryQuery) {
        StringBuilder md = new StringBuilder(4096);
        md.append("### Observation\n\n");
        md.append(observationAnalysisModeHeader());
        md.append("## ⭐ [核心比价数据已送达] 归一化检索 Key: `")
                .append(escapeMd(summaryQuery))
                .append("`\n\n");
        md.append("- **数据来源**：").append(PROVENANCE_ATOMIC_STITCH).append("\n\n");
        String combinedBody = slices.stream().map(EntitySlice::innerMarkdown).reduce("", (a, b) -> a + "\n" + b);
        md.append(coreComparisonDataBanner(summaryQuery, combinedBody));
        for (EntitySlice s : slices) {
            md.append("---\n\n");
            md.append("## 实体：`").append(escapeMd(s.entity())).append("`\n\n");
            md.append("- **片段来源**：").append(s.sourceLabel()).append("\n");
            md.append("- **数据溯源**：").append(s.dataProvenance()).append("\n\n");
            md.append(s.innerMarkdown()).append("\n\n");
        }
        return md.toString().strip();
    }

    /**
     * WiseLink 5.19：先取原始检索片段 → 规则脱水 → HanLP 名词实体 → 去重排序生成归一化 Key；无实体时回退为脱水串（兼容旧缓存/意图逻辑）。
     */
    private static String resolveQuery(Map<String, Object> params, ToolContext toolContext) {
        String raw = resolveRawQueryFragment(params, toolContext);
        return resolveQueryFromRaw(raw);
    }

    private static String resolveRawQueryFragment(Map<String, Object> params, ToolContext toolContext) {
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
        return safeContextFallbackQueryRaw(toolContext);
    }

    private static String safeContextFallbackQueryRaw(ToolContext toolContext) {
        String last = WiseLinkToolSecurityInterceptor.extractUserMessage(toolContext);
        return last == null ? "" : last.strip();
    }

    private static String resolveQueryFromRaw(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String forNlp = dehydrateSearchQueryString(raw.strip());
        if (!StringUtils.hasText(forNlp)) {
            return "";
        }
        List<String> entities;
        try {
            entities = WiseLinkNlpUtil.extractProductEntities(forNlp);
        } catch (Throwable ex) {
            log.warn(">>>> [WiseLink-NLP] HanLP 实体抽取异常，回退脱水串: {}", ex.toString());
            entities = List.of();
        }
        String key = buildNormalizedQueryKey(entities, forNlp);
        if (!StringUtils.hasText(key)) {
            return "";
        }
        if (!acceptableParamQueryText(key) && !acceptableContextFallbackQueryText(key)) {
            return "";
        }
        return key.strip();
    }

    /** 实体去重 + 字典序归一化，供缓存 Key 与老链路对齐。 */
    private static String buildNormalizedQueryKey(List<String> entities, String dehydratedFallback) {
        if (entities == null || entities.isEmpty()) {
            return dehydratedFallback == null ? "" : dehydratedFallback.strip();
        }
        TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String e : entities) {
            if (StringUtils.hasText(e)) {
                sorted.add(e.strip());
            }
        }
        if (sorted.isEmpty()) {
            return dehydratedFallback == null ? "" : dehydratedFallback.strip();
        }
        return String.join(" ", sorted);
    }

    /**
     * 检索词极简脱水（参数 JSON 与 lastUserMessage 共用）：剥指令/连接词/「完成后」等尾巴 → 分词剔除噪声 → 保留「华为 Mate X5 小米 14 Ultra」式实体串。
     */
    private static String dehydrateSearchQueryString(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String t = raw.strip().replaceAll("(?i)Manus", "");
        for (String phrase : CONTEXT_QUERY_NOISE_PHRASES_ORDERED) {
            t = t.replace(phrase, "");
        }
        t = t.replaceAll("对比(?!度)", "");
        t = insertHanLatinBoundaries(t);
        Matcher tm = TOKEN_PATTERN.matcher(t);
        List<String> kept = new ArrayList<>();
        while (tm.find()) {
            String tok = tm.group().strip();
            if (!StringUtils.hasText(tok)) {
                continue;
            }
            if (CONTEXT_QUERY_NOISE_TOKENS.contains(tok) || CONTEXT_QUERY_NOISE_TOKENS.contains(tok.toLowerCase())) {
                continue;
            }
            kept.add(tok);
        }
        if (kept.isEmpty()) {
            return "";
        }
        final int maxChars = 120;
        final int maxTokens = 8;
        if (kept.size() > maxTokens) {
            kept = kept.subList(0, maxTokens);
        }
        String joined = String.join(" ", kept).strip();
        if (joined.length() > maxChars) {
            joined = joined.substring(0, maxChars).strip();
        }
        return joined;
    }

    private static String insertHanLatinBoundaries(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.replaceAll("([\\p{IsHan}])([A-Za-z0-9])", "$1 $2");
        return t.replaceAll("([A-Za-z0-9])([\\p{IsHan}])", "$1 $2");
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
