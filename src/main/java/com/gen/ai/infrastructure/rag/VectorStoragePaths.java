package com.gen.ai.infrastructure.rag;

import java.nio.file.Files;
import java.nio.file.Path;

import com.gen.ai.config.StorageProperties;

/**
 * 与 {@link com.gen.ai.config.StorageConfig}、{@link com.gen.ai.infrastructure.rag.service.RagDataService} 对齐的
 * SimpleVectorStore 索引文件与侧车文件路径解析。
 */
public final class VectorStoragePaths {

    private VectorStoragePaths() {}

    /** {@code app.storage.vector-db} 为目录时指向其下 {@code vector-store.json}，否则为文件本身。 */
    public static Path resolveVectorIndexFile(StorageProperties storageProperties) {
        String vectorDb = storageProperties.getStorage().getVectorDb();
        if (vectorDb == null || vectorDb.isBlank()) {
            return null;
        }
        Path configured = Path.of(vectorDb);
        return Files.isDirectory(configured) ? configured.resolve("vector-store.json") : configured;
    }

    /** 与向量索引同目录的灌库侧车（指纹 + 元数据台账 JSON）。 */
    public static Path resolveIngestionSidecarFile(StorageProperties storageProperties) {
        Path index = resolveVectorIndexFile(storageProperties);
        if (index == null) {
            return null;
        }
        Path parent = index.getParent();
        return parent != null ? parent.resolve("rag-ingestion-sidecar.json") : null;
    }
}
