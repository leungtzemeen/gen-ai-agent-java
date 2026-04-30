package com.gen.ai.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
            new Product("SKU-2002", "Apple AirPods Pro 2", new BigDecimal("1899.00"), 18));

    public BigDecimal getProductPrice(String itemName) {
        /*
         * 3. 【亮点】严谨的防御性编程
         * 使用了 Objects.requireNonNull 和自定义的 requireProduct 方法。
         * 专业性体现：如果 AI 传了个空参数或者查不到商品，它会立刻抛出清晰的异常并记录日志，而不是报一个莫名其妙的空指针错误。
         */
        Objects.requireNonNull(itemName, "itemName");
        Product product = requireProduct(itemName);
        /*
         * 4. 【亮点】日志格式非常“工业级”
         * 它使用了 >>>> [MOCK-DB] ... 这种格式。
         * 实战意义：一会儿我们在控制台看日志时，这种带前缀的日志能帮我们瞬间从满屏的 Spring 日志里抓到 AI “翻牌”数据库的动作。
         */
        log.info(">>>> [MOCK-DB] 价格查询 itemName='{}' -> price={}", itemName, product.price());
        return product.price();
    }

    public int getProductStock(String itemName) {
        /*
         * 3. 【亮点】严谨的防御性编程
         * 使用了 Objects.requireNonNull 和自定义的 requireProduct 方法。
         * 专业性体现：如果 AI 传了个空参数或者查不到商品，它会立刻抛出清晰的异常并记录日志，而不是报一个莫名其妙的空指针错误。
         */
        Objects.requireNonNull(itemName, "itemName");
        Product product = requireProduct(itemName);
        log.info(">>>> [MOCK-DB] 库存查询 itemName='{}' -> stock={}", itemName, product.stock());
        return product.stock();
    }

    private Product requireProduct(String itemName) {
        String key = keyOf(itemName);

        // Step 1: 完全匹配（规范化后）
        Product exact = productsByNameKey.get(key);
        if (exact != null) {
            return exact;
        }

        // Step 2: 模糊匹配（互包含：输入包含库 key 的核心词，或库 key 包含输入）
        Product fuzzy = fuzzyMatch(itemName);
        if (fuzzy != null) {
            log.info(">>>> [MOCK-DB] 模糊匹配成功：输入'{}' -> 库中'{}'。", itemName, fuzzy.name());
            return fuzzy;
        }

        // Step 3: 中英文映射兜底（苹果 <-> Apple）
        String mapped = mapAppleBrand(itemName);
        if (!mapped.equals(itemName)) {
            Product mappedFuzzy = fuzzyMatch(mapped);
            if (mappedFuzzy != null) {
                log.info(">>>> [MOCK-DB] 模糊匹配成功：输入'{}' -> 库中'{}'。", itemName, mappedFuzzy.name());
                return mappedFuzzy;
            }
        }

        log.info(">>>> [MOCK-DB] 未找到商品 itemName='{}' (key='{}')", itemName, key);
        throw new IllegalArgumentException("Unknown itemName: " + itemName);
    }

    /*
     * 2. 【亮点】极其稳健的“模糊匹配”设计 (keyOf)
     * 这是它最聪明的地方！它写了一个 keyOf 方法，把商品名做了 trim() 和 toLowerCase() 处理。
     * 实战意义：AI 提取参数时可能会多带个空格，或者大小写不准（比如写成 "iphone 15"）。有了这个处理，AI 调用的成功率会提升
     * 200%。这说明它考虑到了 AI 调用的不确定性。
     */
    private static String keyOf(String itemName) {
        return itemName.trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern NON_WORDS = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+");

    private Product fuzzyMatch(String inputName) {
        String inputNorm = normalizeForMatch(inputName);
        String inputNoSpace = inputNorm.replace(" ", "");
        if (inputNoSpace.isBlank()) {
            return null;
        }

        for (Map.Entry<String, Product> e : productsByNameKey.entrySet()) {
            String storedKeyNorm = normalizeForMatch(e.getKey());
            String storedKeyNoSpace = storedKeyNorm.replace(" ", "");

            String storedCoreNorm = normalizeForMatch(removeBrandWords(e.getValue().name()));
            String storedCoreNoSpace = storedCoreNorm.replace(" ", "");

            if (containsEitherWay(inputNorm, storedCoreNorm)
                    || containsEitherWay(inputNoSpace, storedCoreNoSpace)
                    || containsEitherWay(inputNorm, storedKeyNorm)
                    || containsEitherWay(inputNoSpace, storedKeyNoSpace)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsEitherWay(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    private static String normalizeForMatch(String name) {
        if (name == null) {
            return "";
        }
        String lowered = name.trim().toLowerCase(Locale.ROOT);
        String spaced = NON_WORDS.matcher(lowered).replaceAll(" ");
        return spaced.replaceAll("\\s+", " ").trim();
    }

    private static String removeBrandWords(String name) {
        String norm = normalizeForMatch(name);
        // 只去掉常见品牌前缀，避免误删型号信息
        return norm.replaceAll("^(苹果|apple|小米|xiaomi|索尼|sony)\\s+", "");
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

    /*
     * 1. 【亮点】使用了 Java 17+ 的 record
     * 它没有用传统的 class + Getter/Setter，而是直接用了 public record Product(...)。
     * 专业性体现：record 是不可变数据对象的最佳实践，代码极其简洁，非常符合现代 Java 开发规范。
     */
    public record Product(String id, String name, BigDecimal price, int stock) {
    }
}
