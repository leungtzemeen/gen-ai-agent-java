package com.gen.ai.config;

import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.gen.ai.service.MockOrderService;

import lombok.RequiredArgsConstructor;

/**
 * 注册 Spring AI Function Calling 所需的工具 Bean（价格、库存等），供 {@link org.springframework.ai.chat.client.ChatClient} 调用。
 */
@Configuration
@RequiredArgsConstructor
public class AiToolsConfig {

    /** 工具入参：商品名称（由模型从用户话术中抽取）。 */
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
