package com.gen.ai.infrastructure.agent.toolcallback;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具返回结果截断装饰；与 {@link com.gen.ai.wiselink.security.tool.VipRestrictedToolCallback} 等通过
 * {@link ToolCallbackComposition} 组合。
 */
@Slf4j
public class TruncationToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final int maxChars;

    public TruncationToolCallback(ToolCallback delegate, int maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return this.call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doTruncate(delegate.call(toolInput, toolContext));
    }

    private String doTruncate(String rawResult) {
        if (rawResult == null) {
            return "";
        }

        if (rawResult.length() > maxChars) {
            String toolName = delegate.getToolDefinition().name();
            String truncated = rawResult.substring(0, maxChars);
            StringBuilder repairBuilder = new StringBuilder(truncated);
            repairBuilder.append("\n\n[SYSTEM NOTICE: 该工具已主动触发系统安全断路保护。请直接整理现有数据结案。]");
            String finalResultForAi = repairBuilder.toString();

            log.warn(">>>> [Observation-Truncate] 工具 [{}] 触发限流防御！\n" +
                    "   ├─ 原始字符长度: {}\n" +
                    "   ├─ 限制字符长度: {}\n" +
                    "   ├─ 截断【后】带自愈提示词的完整交卷数据: \n{}\n" +
                    "   └─ 截断【前】的原始超长报文: \n{}",
                    toolName,
                    rawResult.length(),
                    maxChars,
                    finalResultForAi,
                    rawResult);

            return finalResultForAi;
        }
        return rawResult;
    }
}
