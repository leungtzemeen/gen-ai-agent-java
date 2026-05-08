package com.gen.ai.infrastructure.cache;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

/**
 * 导购物理检索结果缓存：脱水 query 作 Key，24h TTL，降低 Tavily/Serper 额度消耗。
 */
@Service
@RequiredArgsConstructor
public class SearchCacheService {

    private static final String KEY_PREFIX = "wiselink:cache:product:";
    private static final Duration TTL = Duration.ofHours(24);

    /** 口语与噪声词，脱水时剔除（不区分先后出现顺序，全局替换为空串）。 */
    private static final Pattern DEHYDRATE_NOISE =
            Pattern.compile("帮我|查一下|的价格|多少钱|销量|评价|对比|哪里买|怎么样|地址|库存|实时|最新");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 脱水：去掉噪声短语 → 去空白 → 小写，用于稳定缓存 Key。
     */
    private String dehydrate(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String stripped = DEHYDRATE_NOISE.matcher(query.strip()).replaceAll("");
        stripped = WHITESPACE.matcher(stripped).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT);
    }

    /**
     * @return 缓存正文（Markdown 片段）；未命中或 Key 无效时 {@code null}
     */
    public String getCache(String query) {
        String dry = dehydrate(query);
        if (!StringUtils.hasText(dry)) {
            return null;
        }
        String key = KEY_PREFIX + dry;
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void putCache(String query, String result) {
        if (!StringUtils.hasText(result)) {
            return;
        }
        String dry = dehydrate(query);
        if (!StringUtils.hasText(dry)) {
            return;
        }
        String key = KEY_PREFIX + dry;
        stringRedisTemplate.opsForValue().set(key, result, TTL);
    }
}
