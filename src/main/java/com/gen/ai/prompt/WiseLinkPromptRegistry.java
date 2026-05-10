package com.gen.ai.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class WiseLinkPromptRegistry {
    // 全系统所有编译好的模版，全部优雅地躺在这里
    private final Map<String, PromptTemplate> registry = new ConcurrentHashMap<>();

    public void register(String key, String content) {
        if (key == null || key.isBlank()) {
            return;
        }
        // 防御拦截：如果 .st 文件里这个标签下是空的，或者全是空格
        if (content == null || content.isBlank()) {
            log.warn(">>>> [Prompt-Registry-Block] 检测到标签 [{}] 的物理文本为空，挂载安全空格占位符。", key);
            // 内部亲自 new，并用带有一个空格的实体顶住 Spring AI 的 hasText 断言
            this.registry.put(key, new PromptTemplate(" "));
            return;
        }

        // 核心收割：由内部亲自执行 new PromptTemplate！彻底消灭参数摩擦与时空断层
        this.registry.put(key, new PromptTemplate(content.strip()));
        log.info(">>>> [Prompt-Registry-Ready] 标签 [{}] 提示词物理灌注成功，字符数: {}",
                key, content.strip().length());
    }

    /**
     * 【终极自愈入口】：只接受强类型枚举，彻底封死硬编码字符串
     */
    public PromptTemplate get(PromptDefinition definition) {
        if (definition == null) {
            return new PromptTemplate("");
        }
        return this.registry.get(definition.getKey());
    }
}