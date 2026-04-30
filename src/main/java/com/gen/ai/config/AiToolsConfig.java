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
    @Description("用于查询特定商品的实时价格（输入商品名称，返回价格）。")
    public Function<String, BigDecimal> getProductPriceFunction() {
        return mockOrderService::getProductPrice;
    }

    @Bean("getProductStockFunction")
    @Description("用于查询特定商品的实时库存（输入商品名称，返回剩余库存数量）。")
    public Function<String, Integer> getProductStockFunction() {
        return mockOrderService::getProductStock;
    }
}

