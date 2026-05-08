package com.gen.ai.infrastructure.guard;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.gen.ai.common.exception.RateLimitException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 5.14：搜索链路 Redis 限流与意图关键词预筛（后续可在 Controller / Filter 注入调用）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WiseLinkSearchGuard {

    private static final String SESSION_KEY_PREFIX = "wiselink:limit:session:";
    private static final String USER_KEY_PREFIX = "wiselink:limit:user:";
    /** 会话内已执行过物理检索的原子实体（防抖 / 防重复消耗额度） */
    private static final String FOOTPRINT_KEY_PREFIX = "wiselink:footprint:";
    private static final Duration FOOTPRINT_TTL = Duration.ofMinutes(30);
    /** 单会话窗口内允许的「消耗配额」的检索次数（略放宽，覆盖两品对比：搜 A、搜 B、收口）。 */
    private static final int SESSION_MAX = 5;
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int USER_DAILY_MAX = 20;
    private static final Duration USER_TTL = Duration.ofHours(24);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId DAY_ZONE = ZoneId.systemDefault();

    /** 命中任一子串则视为应触发外部搜索/比价类能力（预留意图过滤）。 */
    private static final Set<String> SEARCH_INTENT_KEYWORDS =
            Set.of(
                    "价格",
                    "多少钱",
                    "哪里有",
                    "销量",
                    "京东",
                    "多少",
                    "便宜",
                    "对比",
                    "评价",
                    "参数",
                    "配置",
                    "手机",
                    "电脑",
                    "规格",
                    "链接");

    /**
     * 剥离后若几乎无实质内容，则视为纯寒暄，避免误触发；长型号/具体需求在剥离寒暄后仍保留足够字符则放行。
     */
    private static final Set<String> CHITCHAT_PHRASES =
            Set.of(
                    "你好",
                    "您好",
                    "嗨",
                    "哈喽",
                    "在吗",
                    "在么",
                    "你是谁",
                    "谁啊",
                    "早上好",
                    "晚上好",
                    "中午好",
                    "谢谢",
                    "感谢",
                    "再见",
                    "拜拜",
                    "hello",
                    "hi");

    private final RedisTemplate<String, String> redisTemplate;

    /** 归一化实体名，作为 Redis Set member（小写、trim）。 */
    public static String normalizeFootprintEntity(String entity) {
        if (entity == null) {
            return "";
        }
        return entity.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 若本会话已对该实体做过物理检索，则返回 true（应跳过再次调用外部搜索，走「数据已在上下文」提示）。
     */
    public boolean shouldBlockPhysicalSearch(String sessionId, String entity) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(entity)) {
            return false;
        }
        String key = FOOTPRINT_KEY_PREFIX + sessionId.strip();
        String member = normalizeFootprintEntity(entity);
        if (!StringUtils.hasText(member)) {
            return false;
        }
        Boolean memberOf = redisTemplate.opsForSet().isMember(key, member);
        return Boolean.TRUE.equals(memberOf);
    }

    /** 物理检索成功后写入足迹，与会话窗口 TTL 对齐。 */
    public void recordEntityFootprint(String sessionId, String entity) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(entity)) {
            return;
        }
        String key = FOOTPRINT_KEY_PREFIX + sessionId.strip();
        String member = normalizeFootprintEntity(entity);
        if (!StringUtils.hasText(member)) {
            return;
        }
        Long added = redisTemplate.opsForSet().add(key, member);
        redisTemplate.expire(key, FOOTPRINT_TTL);
        if (Long.valueOf(1L).equals(added)) {
            log.debug(">>>> [WiseLink-Guard] 实体足迹写入 sessionId={} entity={}", sessionId.strip(), member);
        }
    }

    /**
     * 工具链路在命中会话/用户限流时返回给模型的 Observation 全文。
     * <p>
     * 语义为「配额收口、请基于已有 Observation 结案」，避免被模型误判为可重试的任务失败。
     */
    public static String toolRateLimitObservationMarkdown() {
        return """
                ### Observation

                - **数据来源**：实时检索配额已满（正常收口信号，非任务失败）

                [系统指令] 实时检索配额已满。由于你在之前的步骤中已成功获取了部分关键数据（见之前的 Observation），请立即停止检索，并基于现有信息整理输出 FINAL_ANSWER。严禁再次尝试调用搜索工具。
                """
                .strip();
    }

    /**
     * 会话维度 + 用户当日维度双重限流；均通过原子 {@code INCR} 计数，首次写入时设置 TTL。
     *
     * @throws RateLimitException 任一维度超限
     */
    public void checkRateLimit(String sessionId, String userId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("sessionId 与 userId 均不能为空");
        }
        String sid = sessionId.strip();
        String uid = userId.strip();

        String sessionKey = SESSION_KEY_PREFIX + sid;
        Long sessionCount = redisTemplate.opsForValue().increment(sessionKey);
        touchTtlIfFirstWrite(sessionKey, sessionCount, SESSION_TTL);
        if (sessionCount != null && sessionCount > SESSION_MAX) {
            redisTemplate.opsForValue().decrement(sessionKey);
            log.warn(">>>> [WiseLink-Guard] session 限流触发 sessionId={} count>{}", sid, SESSION_MAX);
            throw new RateLimitException("流量触发熔断，请稍后再试或联系管理员");
        }

        String day = LocalDate.now(DAY_ZONE).format(DAY_FMT);
        String userKey = USER_KEY_PREFIX + uid + ":" + day;
        Long userCount = redisTemplate.opsForValue().increment(userKey);
        touchTtlIfFirstWrite(userKey, userCount, USER_TTL);
        if (userCount != null && userCount > USER_DAILY_MAX) {
            redisTemplate.opsForValue().decrement(sessionKey);
            redisTemplate.opsForValue().decrement(userKey);
            log.warn(">>>> [WiseLink-Guard] user 日限流触发 userId={} day={} count>{}", uid, day, USER_DAILY_MAX);
            throw new RateLimitException("流量触发熔断，请稍后再试或联系管理员");
        }
    }

    private void touchTtlIfFirstWrite(String key, Long afterIncr, Duration ttl) {
        if (afterIncr != null && afterIncr == 1L) {
            Boolean ok = redisTemplate.expire(key, ttl);
            if (Boolean.FALSE.equals(ok)) {
                log.debug(">>>> [WiseLink-Guard] expire 未生效（键可能已过期） key={}", key);
            }
        }
    }

    /**
     * 意图预筛：命中导购/检索关键词则 true；否则在查询长度大于 3 且剥离寒暄后仍有实质内容时 true（适配长型号、7B 漏关键词）。
     */
    public boolean shouldTriggerSearch(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String raw = query.strip();
        if (matchesIntentKeyword(raw)) {
            return true;
        }
        if (raw.length() <= 3) {
            return false;
        }
        return hasSubstantiveBeyondChitchat(raw);
    }

    private static boolean matchesIntentKeyword(String raw) {
        String q = raw.toLowerCase(Locale.ROOT);
        for (String kw : SEARCH_INTENT_KEYWORDS) {
            if (q.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSubstantiveBeyondChitchat(String raw) {
        String lower = raw.strip().toLowerCase(Locale.ROOT);
        for (String p : CHITCHAT_PHRASES) {
            lower = lower.replace(p.toLowerCase(Locale.ROOT), " ");
        }
        lower = lower.replaceAll("\\s+", "");
        lower = lower.replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", "");
        return lower.length() >= 2;
    }
}
