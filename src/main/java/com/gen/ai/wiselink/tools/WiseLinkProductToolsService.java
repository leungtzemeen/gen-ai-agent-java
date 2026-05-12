package com.gen.ai.wiselink.tools;

import org.springframework.stereotype.Service;

import com.gen.ai.infrastructure.mock.MockOrderService;
import com.gen.ai.wiselink.annotation.WiseLinkTool;

import lombok.RequiredArgsConstructor;

/**
 * WiseLink 商品工具：按条件查询本地商品目录，返回 JSON 数组字符串供模型向用户展示。
 * <p>
 * 入参均为可选字符串，空串表示不参与该维度过滤。
 */
@Service
@RequiredArgsConstructor
public class WiseLinkProductToolsService {

    private final MockOrderService mockOrderService;

    @WiseLinkTool(
            name = "searchProductsFunction",
            description = "查询商品列表（含名称、预算、规格、价格文案、专家简报、图片等；不含时间戳字段）。"
                    + "keyword：可选，在名称/描述/规格/预算/价格文案中模糊匹配；"
                    + "minPrice、maxPrice：可选数字字符串，按价格文案中解析出的参考价筛选；"
                    + "三者都空则返回默认类目前若干条。返回条数上限由服务端配置。")
    public String searchProducts(String keyword, String minPrice, String maxPrice) {
        return mockOrderService.searchProductsAsJson(
                keyword != null ? keyword : "",
                minPrice != null ? minPrice : "",
                maxPrice != null ? maxPrice : "");
    }
}
