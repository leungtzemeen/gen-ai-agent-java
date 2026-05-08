package com.gen.ai.infrastructure.nlp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class WiseLinkNlpUtilTest {

    @Test
    void extractsNounEntitiesAndDropsBlacklist() {
        List<String> entities =
                WiseLinkNlpUtil.extractProductEntities("京东 华为 Mate X5 和 小米 14 Ultra 价格对比");
        assertThat(entities)
                .isNotEmpty()
                .doesNotContain("京东", "价格", "对比", "手机")
                .anyMatch(s -> s.contains("华为") || s.contains("小米"));
    }

    @Test
    void huaweiMateX5GluesBrandAndLatinModelIntoSingleEntity() {
        List<String> entities = WiseLinkNlpUtil.extractProductEntities("华为MateX5");
        assertThat(entities).containsExactly("华为MateX5");
    }

    @Test
    void abstractBlacklistTokensDroppedFromEntities() {
        assertThat(WiseLinkNlpUtil.isAbstractBlacklistToken("数据")).isTrue();
        assertThat(WiseLinkNlpUtil.isAbstractBlacklistToken("信息")).isTrue();
        List<String> entities = WiseLinkNlpUtil.extractProductEntities("查一下数据和信息报告");
        assertThat(entities).doesNotContain("数据", "信息", "报告");
    }
}
