package com.gen.ai.prompt;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * 将 {@code assistant-guide.st} 拆成「人设系统提示」与 WiseLink RAG 链路透传的模板段落。
 * <p>
 * 分段使用标记 {@code <<<WISELINK_SECTION:NAME>>> ... <<<WISELINK_SECTION_END>>>}，
 * 避免把 {@link PromptTemplate} 的占位符（如 {@code {query}}）混进 {@link org.springframework.ai.chat.prompt.SystemPromptTemplate}。
 */
public final class AssistantGuidePromptBundle {

    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "<<<WISELINK_SECTION:(\\w+)>>>\\s*(.*?)\\s*<<<WISELINK_SECTION_END>>>",
            Pattern.DOTALL);

    private static final Set<String> REQUIRED_SECTIONS = Set.of(
            "COMPRESSION_QUERY_TRANSFORM",
            "CONTEXTUAL_QUERY_AUGMENT");

    private final Resource systemPromptResource;
    private final PromptTemplate compressionQueryTransformTemplate;
    private final PromptTemplate contextualQueryAugmentTemplate;

    private AssistantGuidePromptBundle(
            Resource systemPromptResource,
            PromptTemplate compressionQueryTransformTemplate,
            PromptTemplate contextualQueryAugmentTemplate) {
        this.systemPromptResource = systemPromptResource;
        this.compressionQueryTransformTemplate = compressionQueryTransformTemplate;
        this.contextualQueryAugmentTemplate = contextualQueryAugmentTemplate;
    }

    /**
     * @param fullText 完整 {@code assistant-guide.st} 文本（含分段标记）
     */
    public static AssistantGuidePromptBundle parse(String fullText) {
        Assert.hasText(fullText, "assistant-guide content cannot be empty");

        Map<String, String> sections = new HashMap<>();
        Matcher matcher = SECTION_PATTERN.matcher(fullText);
        while (matcher.find()) {
            sections.put(matcher.group(1), matcher.group(2).strip());
        }

        for (String key : REQUIRED_SECTIONS) {
            Assert.hasText(sections.get(key), "Missing or empty WISELINK_SECTION: " + key);
        }

        String stripped = SECTION_PATTERN.matcher(fullText).replaceAll("").strip();
        Assert.hasText(stripped, "System persona body is empty after stripping WISELINK sections");

        Resource systemResource = new ByteArrayResource(stripped.getBytes(StandardCharsets.UTF_8));
        return new AssistantGuidePromptBundle(
                systemResource,
                new PromptTemplate(sections.get("COMPRESSION_QUERY_TRANSFORM")),
                new PromptTemplate(sections.get("CONTEXTUAL_QUERY_AUGMENT")));
    }

    public Resource systemPromptResource() {
        return systemPromptResource;
    }

    public PromptTemplate compressionQueryTransformTemplate() {
        return compressionQueryTransformTemplate;
    }

    public PromptTemplate contextualQueryAugmentTemplate() {
        return contextualQueryAugmentTemplate;
    }
}
