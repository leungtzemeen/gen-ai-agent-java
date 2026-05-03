package com.gen.ai.wiselink.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 校验 {@link com.gen.ai.wiselink.annotation.WiseLinkTool#enabled()}：禁用的工具不出现在注册表中。
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            // 满足 DashScope 自动配置占位，避免无密钥时上下文启动失败（本测试不发起真实调用）
            "spring.ai.dashscope.api-key=dummy-key-for-registry-feature-switch-test"
        })
class WiseLinkToolRegistryFeatureSwitchTest {

    @Autowired
    private WiseLinkToolRegistry wiseLinkToolRegistry;

    @Test
    void scrapeWebsiteContentDisabled_notInRegisteredCallbacks() {
        assertThat(wiseLinkToolRegistry.getCallbacksByName())
                .doesNotContainKey("scrapeWebsiteContent")
                .containsKey("searchProductOnWeb");
    }
}
