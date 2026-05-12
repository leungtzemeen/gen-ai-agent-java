package com.gen.ai.infrastructure.rag.revision;

import java.util.Optional;

/**
 * 知识源版本指纹：用于「向量快照存在且源未变」时跳过整次 {@code importDocs}。
 * <p>
 * MySQL 等未接入实现时可返回 {@link Optional#empty()}，表示不做启动短路（仍走导入与内存台账判重）。
 */
public interface KnowledgeRevisionFingerprinter {

    /**
     * @param knowledgeType {@code app.knowledge.type}
     * @param bizCategory   默认类目等业务维度，参与指纹避免串类
     * @return 有值则参与与侧车中上次指纹比对；空表示当前类型不支持文件级指纹
     */
    Optional<String> fingerprint(String knowledgeType, String bizCategory);
}
