package com.gen.ai.infrastructure.agent.toolcallback;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.springframework.ai.tool.ToolCallback;

/**
 * 将多个 {@link ToolCallback} 装饰者按固定顺序组合：先应用的层紧贴真实 delegate（最内层），
 * 后应用的层包在最外。典型顺序：鉴权 → 业务 delegate →（返回路径上）截断由最外层完成。
 * <p>
 * 例：{@code compose(leaf, truncation(max))} 得到 {@code TruncationToolCallback(leaf)}。
 */
public final class ToolCallbackComposition {

    /** 单步装饰：输入内层，返回包了一层的外层。 */
    @FunctionalInterface
    public interface Layer extends Function<ToolCallback, ToolCallback> {}

    private ToolCallbackComposition() {}

    /**
     * @param leaf                   最内层（通常是模型最终调用的真实工具）
     * @param innerFirstToOuterLast  从靠近 leaf 的一侧依次向外包裹
     */
    public static ToolCallback compose(ToolCallback leaf, Layer... innerFirstToOuterLast) {
        ToolCallback current = leaf;
        for (Layer layer : innerFirstToOuterLast) {
            current = layer.apply(current);
        }
        return current;
    }

    /** 观测结果截断（最常用作最外层）。 */
    public static Layer truncation(int maxChars) {
        return delegate -> new TruncationToolCallback(delegate, maxChars);
    }

    /** 本请求内工具 leaf 调用总次数上限（紧贴 leaf 一侧包裹）。 */
    public static Layer perRequestBudget(AtomicInteger invokedInRequest, int maxInvocations, String exceededNotice) {
        return delegate -> new PerRequestToolBudgetToolCallback(delegate, invokedInRequest, maxInvocations, exceededNotice);
    }
}
