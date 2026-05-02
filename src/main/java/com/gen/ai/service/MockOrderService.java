package com.gen.ai.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MockOrderService {

    private final Map<String, Product> productsByNameKey = Map.of(
            keyOf("苹果 iPhone 15"), new Product("SKU-1001", "苹果 iPhone 15", new BigDecimal("5999.00"), 12),
            keyOf("小米 14"), new Product("SKU-1002", "小米 14", new BigDecimal("3999.00"), 25),
            keyOf("索尼 WH-1000XM5"), new Product("SKU-2001", "索尼 WH-1000XM5", new BigDecimal("2499.00"), 8),
            keyOf("Apple AirPods Pro 2"),
            new Product("SKU-2002", "Apple AirPods Pro 2", new BigDecimal("1899.00"), 18),
            keyOf("小米电视 S Pro"), new Product("SKU-3001", "小米电视 S Pro 75英寸", new BigDecimal("4999.00"), 10));

    public BigDecimal getProductPrice(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        Product product = requireProduct(itemName);
        log.info(">>>> [MOCK-DB] 价格查询 itemName='{}' -> price={}", itemName, product.price());
        return product.price();
    }

    public int getProductStock(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        Product product = requireProduct(itemName);
        log.info(">>>> [MOCK-DB] 库存查询 itemName='{}' -> stock={}", itemName, product.stock());
        return product.stock();
    }

    /**
     * 遍历 Map 全部 Key：对归一化后的输入与 Key 做双向 contains，命中即返回（不经 map.get 精确查找）。
     */
    private Product requireProduct(String itemName) {
        Product matched = matchNormalized(itemName, itemName);
        if (matched != null) {
            return matched;
        }

        String mapped = mapAppleBrand(itemName);
        if (!mapped.equals(itemName)) {
            matched = matchNormalized(mapped, itemName);
            if (matched != null) {
                return matched;
            }
        }

        log.info(">>>> [MOCK-DB] 未找到商品 itemName='{}'", itemName);
        throw new IllegalArgumentException("Unknown itemName: " + itemName);
    }

    /**
     * @param textToMatch         参与匹配的文本（可与用户原始输入不同，例如品牌映射后）
     * @param originalInputForLog 日志中展示的原始用户查询
     */
    private Product matchNormalized(String textToMatch, String originalInputForLog) {
        String normInput = normalize(textToMatch);
        if (normInput.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Product> e : productsByNameKey.entrySet()) {
            String normKey = normalize(e.getKey());
            if (normKey.isEmpty()) {
                continue;
            }
            if (normInput.contains(normKey) || normKey.contains(normInput)) {
                log.info(
                        ">>>> [MOCK-DB] 归一化匹配成功！[{}] -> [{}].",
                        originalInputForLog,
                        e.getValue().name());
                return e.getValue();
            }
        }
        return null;
    }

    /** 转小写并去除所有空白字符，缩小 AI 入参与 Mock Key 在空格、规格缀写上的差异。 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String keyOf(String itemName) {
        return itemName.trim().toLowerCase(Locale.ROOT);
    }

    private static String mapAppleBrand(String name) {
        if (name == null) {
            return "";
        }
        String out = name;
        if (out.contains("苹果")) {
            out = out.replace("苹果", "Apple");
        }
        if (out.toLowerCase(Locale.ROOT).contains("apple")) {
            out = out.replaceAll("(?i)apple", "苹果");
        }
        return out;
    }

    public record Product(String id, String name, BigDecimal price, int stock) {
    }
}
