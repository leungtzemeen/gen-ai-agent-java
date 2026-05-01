package com.gen.ai.config;

import java.math.BigDecimal;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.gen.ai.service.MockOrderService;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class AiToolsConfig {

    private final MockOrderService mockOrderService;

    @Bean("getProductPriceFunction")
    @Description("用于查询特定商品的实时价格（输入商品名称，返回价格字符串）。")
    public Function<String, String> getProductPriceFunction() {
        return (itemName) -> {
            BigDecimal price = mockOrderService.getProductPrice(itemName);
            return price.toPlainString(); // 💡 强制转为纯数字字符串，如 "1899.00"
        };
    }

    @Bean("getProductStockFunction")
    @Description("用于查询特定商品的实时库存（输入商品名称，返回库存数量字符串）。")
    public Function<String, String> getProductStockFunction() {
        return (itemName) -> {
            Integer stock = mockOrderService.getProductStock(itemName);
            return String.valueOf(stock); // 💡 转为字符串，如 "10"
        };
    }

}
