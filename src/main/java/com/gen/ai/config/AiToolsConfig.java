package com.gen.ai.config;

import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.gen.ai.service.MockOrderService;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class AiToolsConfig {

    public record ItemRequest(String itemName) {
    }

    private final MockOrderService mockOrderService;

    @Bean("getProductPriceFunction")
    @Description("用于查询特定商品的实时价格（参数包含商品名称）。")
    public Function<ItemRequest, String> getProductPriceFunction() {
        return (request) -> mockOrderService.getProductPrice(request.itemName()).toPlainString();
    }

    @Bean("getProductStockFunction")
    @Description("用于查询特定商品的实时库存（参数包含商品名称）。")
    public Function<ItemRequest, String> getProductStockFunction() {
        return (request) -> String.valueOf(mockOrderService.getProductStock(request.itemName()));
    }

}
