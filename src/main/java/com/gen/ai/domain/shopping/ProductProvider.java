package com.gen.ai.domain.shopping;

import java.util.List;
import java.util.Map;

/**
 * 商品数据源万能插槽标准接口
 */
public interface ProductProvider {
    
    /**
     * 根据类目获取全量商品（支持 MySQL / JSON 文件两种实现）
     */
    List<Map<String, Object>> selectByCategory(String category);
}
