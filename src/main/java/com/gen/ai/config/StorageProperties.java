package com.gen.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** {@code app.storage.*}：本地根目录、会话历史、RAG 文档目录与向量索引路径。 */
@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * Root directory for all app storage.
     * Example: ${user.home}/data/gen-ai-agent
     */
    private String root;

    /**
     * Directory for chat history persistence.
     * Example: ${app.storage.root}/history
     */
    private String chatHistory;

    /**
     * Directory for RAG documents.
     * Example: ${app.storage.root}/knowledge
     */
    private String ragDocs;

    /**
     * Directory for vector store persistence.
     * Example: ${app.storage.root}/vector-store
     */
    private String vectorDb;

    // --- 资产收编：在这里直接增加记忆控制参数 ---
    /**
     * 内存滑动窗口：控制 AI 脑力开销 (Token 消耗的关键)
     * 默认值设为 8，兼顾工具调用与省钱
     */
    private int maxMessages = 8;

    /**
     * 磁盘持久化：控制历史文件保存的对话深度
     */
    private int lastNHistory = 12;

     /**
     * 工具返回内容最大字符限制，防止大 JSON 冲垮大模型上下文
     * 推荐值 1500，既保留了高德/比价的关键字段，又过滤了冗余噪音
     */
     private int maxObservationChars = 1500;

    /**
     * 单次 ChatClient 调用（含多轮 model↔tool）内，所有工具真实 leaf 执行次数上限（成功或异常均计数）。
     */
    private int maxToolInvocationsPerRequest = 32;

     // --- 资产收编：知识库（RAG）极致瘦身参数（彻底消灭魔法值） ---
    /**
     * 单次 RAG 知识检索允许带回的最相关文档条数 (Top-K)
     * 强降至 2 条，大幅削减每一轮 ReAct 循环的动态底噪
     */
    private int ragTopK = 2;

    /**
     * 持久化去重探测时的抽取条数（Exists 判定）
     * 物理锁定为 1 条，严禁修改
     */
    private final int duplicateCheckTopK = 1;
}

