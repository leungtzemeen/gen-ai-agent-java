package com.gen.ai.infrastructure.rag.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.VectorStoragePaths;
import com.gen.ai.infrastructure.rag.model.RagDocumentMetadata;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 灌库侧车：持久化「知识源指纹」与「元数据判重台账」，避免判重时反复 {@code similaritySearch} 触发 embedding。
 * <p>
 * 与向量索引同目录的 {@code rag-ingestion-sidecar.json}；MySQL 知识源仅使用台账（指纹由 {@link com.gen.ai.infrastructure.rag.revision.KnowledgeRevisionFingerprinter} 返回空时不做启动短路）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagIngestionSidecar {

    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Object lock = new Object();
    private String lastSourceFingerprint;
    private String lastKnowledgeType;
    private String lastBizCategory;
    private final Map<String, String> jsonGoods = new ConcurrentHashMap<>();
    private final Map<String, String> markdownFiles = new ConcurrentHashMap<>();

    @PostConstruct
    void loadOrBootstrap() {
        Path sidecar = sidecarPath();
        if (sidecar == null) {
            return;
        }
        synchronized (lock) {
            if (Files.isRegularFile(sidecar)) {
                readFromDisk(sidecar);
                log.info(">>>> [RAG-Sidecar] 已加载元数据台账 jsonGoods={} markdownFiles={}", jsonGoods.size(), markdownFiles.size());
                return;
            }
            Path vectorIndex = VectorStoragePaths.resolveVectorIndexFile(storageProperties);
            if (vectorIndex != null && Files.isRegularFile(vectorIndex)) {
                int n = backfillLedgerFromVectorJson(vectorIndex);
                if (n > 0) {
                    log.info(">>>> [RAG-Sidecar] 向量快照存在但侧车缺失，已从向量文件回填 {} 条 goods 元数据（Markdown 需在首次灌库后写入台账）", n);
                    persistUnlocked(sidecar);
                }
            }
        }
    }

    public String getLastSourceFingerprint() {
        return lastSourceFingerprint;
    }

    public void setFingerprintAfterSuccessfulImport(String fingerprint, String knowledgeType, String bizCategory) {
        Path sidecar = sidecarPath();
        if (sidecar == null || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        synchronized (lock) {
            this.lastSourceFingerprint = fingerprint;
            this.lastKnowledgeType = knowledgeType;
            this.lastBizCategory = bizCategory;
            persistUnlocked(sidecar);
        }
    }

    /** JSON：向量已含相同 goods_id + update_time 时返回 true（纯内存/文件，不触发 embedding）。 */
    public boolean hasSameGoodsIdAndUpdateTime(String goodsId, String updateTime) {
        if (goodsId == null || goodsId.isBlank() || "null".equals(goodsId)) {
            return false;
        }
        if (updateTime == null || updateTime.isBlank() || "null".equals(updateTime)) {
            return false;
        }
        String v = jsonGoods.get(goodsId);
        return v != null && updateTime.equals(v);
    }

    /** Markdown：同 source + file_hash 已入账则 true。 */
    public boolean hasSameSourceAndHash(String source, String fileHash) {
        if (source == null || source.isBlank() || "null".equals(source)) {
            return false;
        }
        if (fileHash == null || fileHash.isBlank() || "null".equals(fileHash)) {
            return false;
        }
        String v = markdownFiles.get(source);
        return v != null && fileHash.equals(v);
    }

    /** JSON 灌库删旧前移除台账，避免与旧 update_time 混淆。 */
    public void removeJsonGoods(String goodsId) {
        if (goodsId != null && !goodsId.isBlank()) {
            jsonGoods.remove(goodsId);
        }
    }

    public void putJsonGoods(String goodsId, String updateTime) {
        if (goodsId != null && !goodsId.isBlank() && updateTime != null && !updateTime.isBlank()) {
            jsonGoods.put(goodsId, updateTime);
        }
    }

    public void removeMarkdownSource(String source) {
        if (source != null && !source.isBlank()) {
            markdownFiles.remove(source);
        }
    }

    public void putMarkdownFile(String source, String fileHash) {
        if (source != null && !source.isBlank() && fileHash != null && !fileHash.isBlank()) {
            markdownFiles.put(source, fileHash);
        }
    }

    public void persistAfterMutation() {
        Path sidecar = sidecarPath();
        if (sidecar == null) {
            return;
        }
        synchronized (lock) {
            persistUnlocked(sidecar);
        }
    }

    /** 删除向量数据时一并删除侧车（与 {@link com.gen.ai.infrastructure.rag.service.RagDataService#deleteSimpleVectorStoreFiles} 对齐调用）。 */
    public void deleteSidecarFileIfExists() {
        Path sidecar = sidecarPath();
        if (sidecar == null) {
            return;
        }
        synchronized (lock) {
            try {
                Files.deleteIfExists(sidecar);
            } catch (IOException e) {
                log.warn(">>>> [RAG-Sidecar] 删除侧车文件失败: {}", sidecar, e);
            }
            jsonGoods.clear();
            markdownFiles.clear();
            lastSourceFingerprint = null;
            lastKnowledgeType = null;
            lastBizCategory = null;
        }
    }

    private Path sidecarPath() {
        return VectorStoragePaths.resolveIngestionSidecarFile(storageProperties);
    }

    private void readFromDisk(Path sidecar) {
        try {
            JsonNode root = objectMapper.readTree(Files.newInputStream(sidecar));
            this.lastSourceFingerprint = text(root.get("sourceFingerprint"));
            this.lastKnowledgeType = text(root.get("knowledgeType"));
            this.lastBizCategory = text(root.get("bizCategory"));
            jsonGoods.clear();
            markdownFiles.clear();
            putAllFromJson(root.get("jsonGoods"), jsonGoods);
            putAllFromJson(root.get("markdownFiles"), markdownFiles);
        } catch (Exception e) {
            log.warn(">>>> [RAG-Sidecar] 读取侧车失败，将视为空台账: {}", e.getMessage());
        }
    }

    private static void putAllFromJson(JsonNode node, Map<String, String> target) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(e -> target.put(e.getKey(), e.getValue().asText()));
    }

    private void persistUnlocked(Path sidecar) {
        try {
            Path parent = sidecar.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("sourceFingerprint", lastSourceFingerprint != null ? lastSourceFingerprint : "");
            root.put("knowledgeType", lastKnowledgeType != null ? lastKnowledgeType : "");
            root.put("bizCategory", lastBizCategory != null ? lastBizCategory : "");
            ObjectNode jg = objectMapper.createObjectNode();
            jsonGoods.forEach(jg::put);
            root.set("jsonGoods", jg);
            ObjectNode md = objectMapper.createObjectNode();
            markdownFiles.forEach(md::put);
            root.set("markdownFiles", md);
            Files.writeString(sidecar, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error(">>>> [RAG-Sidecar] 持久化侧车失败: {}", sidecar, e);
        }
    }

    private int backfillLedgerFromVectorJson(Path vectorIndex) {
        try {
            JsonNode root = objectMapper.readTree(Files.newInputStream(vectorIndex));
            Map<String, String> tmp = new HashMap<>();
            walkCollectGoodsMetadata(root, tmp);
            jsonGoods.putAll(tmp);
            return tmp.size();
        } catch (Exception e) {
            log.warn(">>>> [RAG-Sidecar] 从向量索引回填台账失败: {}", e.getMessage());
        }
        return 0;
    }

    /** 深度遍历，收集含 {@code goods_id} 的 metadata 对象（Spring AI 持久化结构兼容）。 */
    private static void walkCollectGoodsMetadata(JsonNode n, Map<String, String> out) {
        if (n == null || n.isMissingNode()) {
            return;
        }
        if (n.isArray()) {
            n.forEach(x -> walkCollectGoodsMetadata(x, out));
            return;
        }
        if (!n.isObject()) {
            return;
        }
        JsonNode meta = n.get("metadata");
        if (meta != null && meta.isObject()) {
            String gid = text(meta.get(RagDocumentMetadata.GOODS_ID));
            if (!gid.isEmpty()) {
                String ut = text(meta.get(RagDocumentMetadata.UPDATE_TIME));
                out.put(gid, ut != null ? ut : "");
            }
        }
        var it = n.fields();
        while (it.hasNext()) {
            walkCollectGoodsMetadata(it.next().getValue(), out);
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) {
            return "";
        }
        return n.asText("");
    }
}
