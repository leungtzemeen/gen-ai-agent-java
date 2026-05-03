package com.gen.ai.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.gen.ai.config.AppSecurityProperties;

import cn.hutool.dfa.WordTree;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地敏感词检测：公开词库（classpath Base64 词表）+ 可选影子词（配置项 Base64），基于 Hutool {@link WordTree} DFA。
 */
@Service
@Slf4j
public class SensitiveWordService {

    private final WordTree wordTree = new WordTree();
    private final AppSecurityProperties appSecurityProperties;

    /**
     * 路 A（公开词）：从 classpath 读取“混淆词库”，用于放置普通“广告词/水军词”等非敏感但需要拦截的词条。
     * <p>
     * 文件内容为 Base64 编码的 UTF-8 文本；解码后按行切分，每行一条敏感词。
     */
    private final Resource sensitiveWordsBinResource;

    public SensitiveWordService(
            AppSecurityProperties appSecurityProperties,
            @Value("classpath:/sensitive_words.bin") Resource sensitiveWordsBinResource) {
        this.appSecurityProperties = Objects.requireNonNull(appSecurityProperties, "appSecurityProperties");
        this.sensitiveWordsBinResource = sensitiveWordsBinResource;
    }

    @PostConstruct
    public void init() {
        int loadedPublic = loadPublicWordsFromBin();
        int loadedShadow = loadShadowWordsFromBase64();
        log.info(">>>> [Security] 敏感词库初始化完成：公开词={}，影子词={}", loadedPublic, loadedShadow);
    }

    private int loadPublicWordsFromBin() {
        if (sensitiveWordsBinResource == null || !sensitiveWordsBinResource.exists()) {
            log.warn(">>>> [Security] sensitive_words.bin 不存在，已跳过公开词加载");
            return 0;
        }

        int loaded = 0;
        try {
            // 1. 完整读取 bin（文本形态 Base64）字节
            byte[] encodedBytes = sensitiveWordsBinResource.getInputStream().readAllBytes();
            if (encodedBytes.length == 0) {
                return 0;
            }

            String encoded = new String(encodedBytes, StandardCharsets.UTF_8);
            // Base64 正文可能被折行；MIME 解码会忽略其中的 \\r\\n 与空格，避免 Standard Decoder 解码不全
            byte[] plainBytes = Base64.getMimeDecoder().decode(encoded.stripLeading().stripTrailing());
            String decoded = new String(plainBytes, StandardCharsets.UTF_8);

            // 2. 按行切分（兼容所有换行符）
            String[] lines = decoded.split("\\R");
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String word = line.trim();
                // 3. 跳过空行与注释行
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                wordTree.addWord(word);
                loaded++;
            }
        } catch (Exception e) {
            // 不影响应用启动：公开词加载失败时仍可依赖影子词（如果已配置）
            log.error(">>>> [Security] 公开词加载失败（已忽略）", e);
        }
        return loaded;
    }

    /**
     * 路 B（影子词）：从 {@link AppSecurityProperties#getShadowWordsBase64()} 读取 Base64 词条并解码入库。
     */
    private int loadShadowWordsFromBase64() {
        List<String> shadowWords = appSecurityProperties.getShadowWordsBase64();
        if (shadowWords == null || shadowWords.isEmpty()) {
            log.info(">>>> [Security] 未配置 app.security.shadow-words-base64，已跳过影子词加载");
            return 0;
        }

        int loaded = 0;
        for (String encoded : shadowWords) {
            if (encoded == null || encoded.isBlank()) {
                continue;
            }
            try {
                String decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8).trim();
                if (decoded.isEmpty()) {
                    continue;
                }
                wordTree.addWord(decoded);
                loaded++;
            } catch (Exception e) {
                // 影子词单条失败不影响其他条目加载
                log.warn(">>>> [Security] 影子词解码失败（已跳过）");
            }
        }
        return loaded;
    }

    /**
     * 判断文本是否包含敏感词（DFA 匹配）。
     */
    public boolean containsSensitiveWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            return wordTree.isMatch(text);
        } catch (Exception e) {
            // 防御性：匹配异常时不拦截，避免误伤业务主流程
            log.error(">>>> [Security] 敏感词检测异常（已放行）", e);
            return false;
        }
    }
}

