package com.gen.ai.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * {@code app.security.*} 绑定配置。
 * <p>
 * YAML 列表 {@code shadow-words-base64} 通过宽松绑定映射到 {@link #shadowWordsBase64}，
 * 避免 {@code @Value} 对横杠列表注入不稳定的问题。
 */
@Data
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    /**
     * Base64 编码的影子敏感词（UTF-8 明文逐条解码后加入 DFA）。
     */
    private List<String> shadowWordsBase64 = new ArrayList<>();
}
