package com.gen.ai.infrastructure.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.common.Term;

/**
 * WiseLink 5.22：基于 HanLP 的导购检索词实体抽取（名词族 + 拉丁型号强制保留 + 中英边界与字母数字 run 合并 +
 * 品牌-型号邻近粘连：名词 n 后紧邻纯拉丁型号时合并为单一商品全名 Key）。
 */
public final class WiseLinkNlpUtil {

    /**
     * 商品实体黑名单：含平台/场景词 + 抽象泛词（语义防火墙，避免对「数据」「报告」等做无效物理检索）。
     */
    private static final Set<String> ENTITY_BLACKLIST =
            Set.of(
                    "京东",
                    "价格",
                    "销量",
                    "手机",
                    "对比",
                    "模式",
                    "任务",
                    "导购",
                    "数据",
                    "信息",
                    "内容",
                    "结果",
                    "报告",
                    "建议",
                    "详情",
                    "参数",
                    "规格",
                    "指令",
                    "专业版",
                    "启动",
                    "进行",
                    "看看",
                    "搜索",
                    "调研");

    private static final Pattern PURE_LATIN_MODEL = Pattern.compile("^[a-zA-Z0-9]+$");

    private WiseLinkNlpUtil() {}

    /** 供导购层「纯度校验」：整段 Key 命中抽象黑名单则禁止点火 / 读脏缓存。 */
    public static boolean isAbstractBlacklistToken(String word) {
        if (!StringUtils.hasText(word)) {
            return true;
        }
        return ENTITY_BLACKLIST.contains(word.strip());
    }

    /**
     * 从查询句中抽取疑似商品实体：
     * <ul>
     *   <li>词性以 {@code n} 开头（名词族），长度 &gt; 1，且不在黑名单；或</li>
     *   <li>纯英文数字片段 {@code ^[a-zA-Z0-9]+$} 且长度 &gt; 1（强制保留，不要求 n 词性）。</li>
     * </ul>
     * 中英粘连（如「华为MateX5」）先插边界；相邻拉丁/数字分词会合并为单一型号 run（如 Mate + X5 → MateX5）；
     * 若名词块后紧跟拉丁型号块（如「华为」「MateX5」），强制粘连为「华为MateX5」，保证缓存/检索 Key 语义完整。
     */
    public static List<String> extractProductEntities(String query) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(query)) {
            return out;
        }
        String prepared = insertHanLatinBoundaries(query.strip());
        List<Term> rawTerms = HanLP.segment(prepared);
        List<WordTag> chunks = mergeAdjacentAlphanumericRuns(rawTerms);
        chunks = mergeNounFollowedByLatinModel(chunks);
        for (WordTag chunk : chunks) {
            String word = chunk.word();
            if (word.length() <= 1) {
                continue;
            }
            if (ENTITY_BLACKLIST.contains(word)) {
                continue;
            }
            boolean forceLatin = PURE_LATIN_MODEL.matcher(word).matches();
            boolean nounFamily = isNounFamily(chunk.nature());
            if (forceLatin || nounFamily) {
                out.add(word);
            }
        }
        return out;
    }

    private static boolean isNounFamily(Nature nature) {
        if (nature == null) {
            return false;
        }
        String pos = nature.toString();
        return !pos.isEmpty() && pos.charAt(0) == 'n';
    }

    /** 汉字与 ASCII 字母数字交界处插入空格，便于分词得到「华为」「MateX5」等原子。 */
    private static String insertHanLatinBoundaries(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.replaceAll("([\\p{IsHan}])([A-Za-z0-9])", "$1 $2");
        return t.replaceAll("([A-Za-z0-9])([\\p{IsHan}])", "$1 $2");
    }

    /**
     * 将连续的被切成多段的拉丁/数字 token（如 Mate + X5）合并为一条型号，便于与汉字实体并列。
     */
    private static List<WordTag> mergeAdjacentAlphanumericRuns(List<Term> terms) {
        List<WordTag> out = new ArrayList<>();
        if (terms == null || terms.isEmpty()) {
            return out;
        }
        StringBuilder acc = new StringBuilder();
        Nature accNature = null;
        for (Term term : terms) {
            if (term == null || !StringUtils.hasText(term.word)) {
                continue;
            }
            String w = term.word.strip();
            if (w.isEmpty()) {
                continue;
            }
            if (PURE_LATIN_MODEL.matcher(w).matches()) {
                if (acc.isEmpty()) {
                    accNature = term.nature;
                }
                acc.append(w);
            } else {
                flushAlphanumericRun(out, acc, accNature);
                accNature = null;
                out.add(new WordTag(w, term.nature));
            }
        }
        flushAlphanumericRun(out, acc, accNature);
        return out;
    }

    /**
     * 名词（n 族）与紧随其后的纯拉丁型号无空格粘连，避免 Redis Key 退化为「华为」「MateX5」等语义残缺片段。
     */
    private static List<WordTag> mergeNounFollowedByLatinModel(List<WordTag> chunks) {
        List<WordTag> merged = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return merged;
        }
        int i = 0;
        while (i < chunks.size()) {
            WordTag cur = chunks.get(i);
            if (i + 1 < chunks.size()
                    && eligibleNounChunkForBrandGlue(cur)
                    && isPureLatinModelToken(chunks.get(i + 1).word())) {
                WordTag next = chunks.get(i + 1);
                String combined = cur.word() + next.word();
                merged.add(new WordTag(combined, cur.nature()));
                i += 2;
            } else {
                merged.add(cur);
                i += 1;
            }
        }
        return merged;
    }

    private static boolean eligibleNounChunkForBrandGlue(WordTag chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.word())) {
            return false;
        }
        String w = chunk.word().strip();
        if (w.length() <= 1 || ENTITY_BLACKLIST.contains(w)) {
            return false;
        }
        return isNounFamily(chunk.nature());
    }

    private static boolean isPureLatinModelToken(String word) {
        if (!StringUtils.hasText(word)) {
            return false;
        }
        String w = word.strip();
        return w.length() > 1 && PURE_LATIN_MODEL.matcher(w).matches();
    }

    private static void flushAlphanumericRun(List<WordTag> out, StringBuilder acc, Nature accNature) {
        if (acc.isEmpty()) {
            return;
        }
        out.add(new WordTag(acc.toString(), accNature));
        acc.setLength(0);
    }

    private record WordTag(String word, Nature nature) {}
}
