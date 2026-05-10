package com.gen.ai.prompt;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

/**
 * 将 {@code assistant-guide.st} 拆成「人设系统提示」与 WiseLink RAG 链路透传的模板段落。
 * <p>
 * 分段使用标记 {@code <<<WISELINK_SECTION:NAME>>> ... <<<WISELINK_SECTION_END>>>}，
 * 避免把 {@link PromptTemplate} 的占位符（如 {@code {query}}）混进
 * {@link org.springframework.ai.chat.prompt.SystemPromptTemplate}。
 */
@Slf4j
public final class AssistantGuidePromptBundle {

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "<<<WISELINK_SECTION:(\\w+)>>>\\s*(.*?)\\s*<<<WISELINK_SECTION_END>>>",
            Pattern.DOTALL);

    private final Resource systemResource;

    private AssistantGuidePromptBundle(Resource systemResource, Map<String, String> sections, WiseLinkPromptRegistry registry) {
        this.systemResource = systemResource;
        
        if (sections != null && registry != null) {
            sections.forEach((key, content) -> {
                if (content != null && !content.isBlank()) {
                    registry.register(key, content);
                }
            });
        }
    }

    /**
     * @param fullText 完整 {@code assistant-guide.st} 文本（含分段标记）
     */
    public static AssistantGuidePromptBundle parse(String fullText, WiseLinkPromptRegistry  registry) {
        Assert.hasText(fullText, "assistant-guide content cannot be empty");

        Map<String, String> sections = new HashMap<>();
        Matcher matcher = SECTION_PATTERN.matcher(fullText);
        while (matcher.find()) {
            sections.put(matcher.group(1), matcher.group(2).strip());
        }
        // 剔除标签内容，把所有带标签的部分全部删掉
        String stripped = SECTION_PATTERN.matcher(fullText).replaceAll("").strip();
        Assert.hasText(stripped, "System persona body is empty after stripping WISELINK sections");

        Resource systemResource = new ByteArrayResource(stripped.getBytes(StandardCharsets.UTF_8));
        log.info(">>>> [Bundle-Core] 静态解析完成，共提取出 {} 个动态 Section 文本", sections.size());
        // 完美结案：一行构造函数，直接解决现在、过去、和未来的所有扩展！
        return new AssistantGuidePromptBundle(systemResource, sections, registry);
    }

    public Resource getSystemResource() {
        return this.systemResource;
    }
}
