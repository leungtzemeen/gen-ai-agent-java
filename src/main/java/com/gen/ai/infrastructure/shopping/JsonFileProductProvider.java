package com.gen.ai.infrastructure.shopping;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gen.ai.domain.shopping.ProductProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
// 灵魂配置：当 yml 里配置 app.datasource.type=json 时，这个组件才会被容器唤醒挂载！
@ConditionalOnProperty(name = "app.datasource.type", havingValue = "json", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class JsonFileProductProvider implements ProductProvider {

    /** 与 RAG 共用 {@code app.storage.rag-docs} 下的商品知识库文件。 */
    @Value("file:${app.storage.rag-docs}/goods_knowledge_base.json")
    private Resource jsonResource;
    private final ObjectMapper objectMapper;

    @Override
    public List<Map<String, Object>> selectByCategory(String category) {
        try {
            log.info(">>>> [DataSource-JSON] 正在从物理真理文件读取类目: [{}] 的商品记录...", category);
            String rawJson = jsonResource.getContentAsString(StandardCharsets.UTF_8);
            List<Map<String, Object>> allProducts = objectMapper.readValue(rawJson, new TypeReference<List<Map<String, Object>>>() {});
            
            // 在内存中过滤出对应类目的数据，模拟 SQL 的 WHERE 过滤
            return allProducts.stream()
                    .filter(p -> category.equalsIgnoreCase(String.valueOf(p.get("biz_category"))))
                    .toList();
        } catch (Exception e) {
            log.error(">>>> [DataSource-JSON-Error] 物理真理文件读取失败！", e);
            return Collections.emptyList();
        }
    }
}
