package com.gen.ai.rag;

import java.util.List;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.util.CollectionUtils;

/**
 * 无多轮历史时跳过压缩改写，避免多余的 LLM 调用；有历史时委托 {@link org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer}。
 */
public final class ShortCircuitCompressionQueryTransformer implements QueryTransformer {

    private final QueryTransformer delegate;

    public ShortCircuitCompressionQueryTransformer(QueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        List<?> history = query.history();
        if (CollectionUtils.isEmpty(history)) {
            return query;
        }
        return delegate.transform(query);
    }
}
