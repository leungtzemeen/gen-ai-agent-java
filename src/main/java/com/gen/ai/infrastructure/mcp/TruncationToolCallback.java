package com.gen.ai.infrastructure.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TruncationToolCallback implements ToolCallback {

    private final ToolCallback delegate; // 真正搬运数据的原生工具
    private final int maxChars; // 截断阈值

    public TruncationToolCallback(ToolCallback delegate, int maxChars) {
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    // --- 适配最新 Spring AI 标准，透传工具定义 ---
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return this.call(toolInput, null);
    }

    /**
     * 2. 灵魂绝杀：适配 1.1.3 标准的带上下文流式拦截方法
     * 无论换什么大模型，只要它触发工具调用，Spring AI 都会严格走这个入口
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return doTruncate(delegate.call(toolInput, toolContext));
    }

    // --- 物理截断逻辑核心逻辑 ---
    private String doTruncate(String rawResult) {
        if (rawResult == null) {
            return "";
        }

        // 物理边界防御：超过阈值强行斩断
        if (rawResult.length() > maxChars) {
            String toolName = delegate.getToolDefinition().name();

            // 1. 纯净切片：一刀切断，绝不留任何拖泥带水的括号补齐逻辑
            String truncated = rawResult.substring(0, maxChars);

            StringBuilder repairBuilder = new StringBuilder(truncated);

            // 2. 灵魂对齐：直接粘上你的最高断路通知，长文本末尾效应瞬间生效
            repairBuilder.append("\n\n[SYSTEM NOTICE: 该工具已主动触发系统安全断路保护。请直接整理现有数据结案。]");

            String finalResultForAi = repairBuilder.toString();

            // 3. 完美保持你那套极具视觉工业美感的日志占位符排版
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
