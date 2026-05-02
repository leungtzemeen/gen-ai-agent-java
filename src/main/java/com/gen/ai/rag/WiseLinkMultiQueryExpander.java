package com.gen.ai.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink「分身检索」：将压缩后的检索 query 扩展为 3 条语义相近、表述不同的短查询并并行召回。
 * <p>
 * 语义对齐 Spring AI {@link org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander}，
 * 保留更强解析与兜底逻辑（行数不足时回填原句），避免线上因模型格式抖动导致退化为单路检索。
 */
@Slf4j
public final class WiseLinkMultiQueryExpander implements QueryExpander {

    private final ChatClient chatClient;

    public WiseLinkMultiQueryExpander(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public List<Query> expand(Query query) {
        List<String> variants = multiQueryExpand(query.text());
        return variants.stream().map(t -> query.mutate().text(t).build()).toList();
    }

    private List<String> multiQueryExpand(String userQuery) {
        String q = userQuery == null ? "" : userQuery.strip();
        if (!StringUtils.hasText(q)) {
            return List.of("");
        }
        try {
            String systemPrompt = """
                    你是电商导购场景的查询改写助手。根据用户输入生成 3 条语义相关但角度不同的短检索查询（每条不超过 24 个字），用于向量库召回。
                    严格只输出 3 行文本：每行一条查询，不要序号、不要解释、不要空行、不要引号。""";
            String raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user("用户问题：\n" + q)
                    .call()
                    .content();
            List<String> lines = normalizeExpandedLines(raw);
            while (lines.size() < 3) {
                lines.add(q);
            }
            List<String> three = List.of(lines.get(0), lines.get(1), lines.get(2));
            log.info(
                    ">>>> [RAG][WiseLink-分身] AI 改写检索词：① {} ② {} ③ {}",
                    three.get(0),
                    three.get(1),
                    three.get(2));
            return three;
        } catch (Exception e) {
            log.warn(">>>> [RAG][WiseLink-分身] 查询改写失败，回退为原始查询。原因：{}", e.toString());
            return List.of(q);
        }
    }

    private static List<String> normalizeExpandedLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            t = t.replaceFirst("^\\d+[\\.、:：\\)]\\s*", "");
            t = t.replaceFirst("^[-*]\\s+", "");
            if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("「") && t.endsWith("」"))) {
                t = t.substring(1, t.length() - 1).strip();
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
