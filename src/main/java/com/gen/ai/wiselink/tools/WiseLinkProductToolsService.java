package com.gen.ai.wiselink.tools;

import org.springframework.stereotype.Service;

import com.gen.ai.infrastructure.mock.MockOrderService;
import com.gen.ai.wiselink.annotation.WiseLinkTool;

import lombok.RequiredArgsConstructor;

/**
 * WiseLink 商品类工具：价格 / 库存查询（由 {@link com.gen.ai.wiselink.registry.WiseLinkToolRegistry} 扫描注册）。
 */
@Service
@RequiredArgsConstructor
public class WiseLinkProductToolsService {

    /** 工具入参：商品名称（由模型从用户话术中抽取）。 */
    public record ItemRequest(String itemName) {
    }

    private final MockOrderService mockOrderService;

    @WiseLinkTool(
            name = "getProductPriceFunction",
            description = "用于查询特定商品的实时价格（参数包含商品名称）。")
    public String getProductPrice(ItemRequest request) {
        return mockOrderService.getProductPrice(request.itemName()).toPlainString();
    }

    @WiseLinkTool(
            name = "getProductStockFunction",
            description = "用于查询特定商品的实时库存（参数包含商品名称）。")
    public String getProductStock(ItemRequest request) {
        return String.valueOf(mockOrderService.getProductStock(request.itemName()));
    }
}
