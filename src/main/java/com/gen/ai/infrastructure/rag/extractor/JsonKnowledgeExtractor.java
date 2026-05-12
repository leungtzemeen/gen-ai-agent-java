package com.gen.ai.infrastructure.rag.extractor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.infrastructure.rag.context.JsonKnowledgeContextStrategy;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;
import com.gen.ai.infrastructure.rag.model.RagDocumentMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 数组知识库解析器：按 {@link KnowledgeLocationContext#getBizCategory()} 过滤行，将每行转为一条可检索的 {@link Document}，
 * 并写入 {@link RagDocumentMetadata} 约定的 metadata（含商品主键与时间字段）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JsonKnowledgeExtractor implements KnowledgeExtractor {

    private final ObjectMapper objectMapper;

    /** @return 固定为 {@code json}，与 {@code app.knowledge.type} 对齐 */
    @Override
    public String supportType() {
        return "json";
    }

    /**
     * 读取凭证中的 JSON 文件，解析为对象列表，过滤类目与 {@code goods_id}，组装正文与 metadata。
     *
     * @param context 由 {@link JsonKnowledgeContextStrategy} 等组装的提货上下文
     * @return 解析失败或资源缺失时返回空列表
     */
    @Override
    public List<Document> extract(KnowledgeLocationContext context) {
        try {
            Resource resource = context.getFileResource();
            if (resource == null) {
                log.warn(">>>> [Extractor-JSON] 提货凭证内未检测到合法的配置文件资源！");
                return Collections.emptyList();
            }
            String category = context.getBizCategory();
            String rawJson = resource.getContentAsString(StandardCharsets.UTF_8);
            List<Map<String, Object>> table = objectMapper.readValue(rawJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            String sourceName = resource.getFilename() != null ? resource.getFilename() : "goods_knowledge_base.json";

            return table.stream()
                    .filter(p -> category.equalsIgnoreCase(String.valueOf(p.get("biz_category"))))
                    .filter(p -> p.get("goods_id") != null)
                    .map(prod -> {
                        String textContent = String.format("产品名称：%s。预算区间：%s。价格信息：%s。核心卖点特性：%s。专家综合测试简报：%s。",
                                prod.get("product_name"),
                                prod.get("budget_range"),
                                prod.get("price_info"),
                                prod.get("core_specs") != null ? prod.get("core_specs").toString() : "[]",
                                prod.get("brief_review"));

                        Object ut = prod.get("update_time");
                        if (ut == null) {
                            ut = prod.get("updateTime");
                        }
                        String updateTime = ut != null ? String.valueOf(ut).trim() : "";

                        String createTime = stringField(prod, "create_time", "createTime");
                        String insertTime = stringField(prod, "insert_time", "insertTime");

                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put(RagDocumentMetadata.GOODS_ID, String.valueOf(prod.get("goods_id")));
                        metadata.put(RagDocumentMetadata.UPDATE_TIME, updateTime);
                        if (!createTime.isEmpty()) {
                            metadata.put(RagDocumentMetadata.CREATE_TIME, createTime);
                        }
                        if (!insertTime.isEmpty()) {
                            metadata.put(RagDocumentMetadata.INSERT_TIME, insertTime);
                        }
                        metadata.put(RagDocumentMetadata.BIZ_CATEGORY, category);
                        metadata.put("image_url", prod.get("image_url") != null ? prod.get("image_url") : "wiselink.wiki");
                        metadata.put(RagDocumentMetadata.SOURCE, sourceName);

                        return new Document(textContent, metadata);
                    })
                    .toList();
        } catch (Exception e) {
            log.error(">>>> [Extractor-JSON] 解析 JSON 知识库失败", e);
            return Collections.emptyList();
        }
    }

    /** 依次尝试 snake_case / camelCase 键，读取非空字符串；缺失则返回空串。 */
    private static String stringField(Map<String, Object> row, String snakeKey, String camelKey) {
        Object v = row.get(snakeKey);
        if (v == null) {
            v = row.get(camelKey);
        }
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v).trim();
        return "null".equals(s) ? "" : s;
    }
}
