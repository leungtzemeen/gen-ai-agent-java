package com.gen.ai.infrastructure.rag.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.infrastructure.rag.VectorStoragePaths;
import com.gen.ai.infrastructure.rag.ingestion.KnowledgeVectorIngester;
import com.gen.ai.infrastructure.rag.ingestion.RagIngestionSidecar;
import com.gen.ai.infrastructure.rag.pipeline.KnowledgeImportPipeline;
import com.gen.ai.infrastructure.rag.revision.KnowledgeRevisionFingerprinter;
import com.gen.ai.infrastructure.rag.query.WiseLinkMultiQueryExpander;

import lombok.extern.slf4j.Slf4j;

/**
 * RAG 数据管线：多源知识导入（{@link KnowledgeImportPipeline} → {@link KnowledgeVectorIngester}）、向量索引持久化/清空、
 * 按品类过滤与 WiseLink 多查询并行检索。
 */
@Service
@Slf4j
public class RagDataService {

    private final VectorStore vectorStore;
    private final StorageProperties storageProperties;
    private final QueryExpander wiseLinkMultiQueryExpander;
    private final KnowledgeImportPipeline knowledgeImportPipeline;
    private final KnowledgeVectorIngester knowledgeVectorIngester;
    private final KnowledgeRevisionFingerprinter knowledgeRevisionFingerprinter;
    private final RagIngestionSidecar ragIngestionSidecar;

    public RagDataService(
            VectorStore vectorStore,
            StorageProperties storageProperties,
            WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander,
            KnowledgeImportPipeline knowledgeImportPipeline,
            KnowledgeVectorIngester knowledgeVectorIngester,
            KnowledgeRevisionFingerprinter knowledgeRevisionFingerprinter,
            RagIngestionSidecar ragIngestionSidecar) {
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.storageProperties = Objects.requireNonNull(storageProperties);
        this.wiseLinkMultiQueryExpander = Objects.requireNonNull(wiseLinkMultiQueryExpander);
        this.knowledgeImportPipeline = Objects.requireNonNull(knowledgeImportPipeline);
        this.knowledgeVectorIngester = Objects.requireNonNull(knowledgeVectorIngester);
        this.knowledgeRevisionFingerprinter = Objects.requireNonNull(knowledgeRevisionFingerprinter);
        this.ragIngestionSidecar = Objects.requireNonNull(ragIngestionSidecar);
    }

    /**
     * 从配置的知识源（{@code app.knowledge.type}）经 {@link KnowledgeImportPipeline} 拉取 {@link Document}，
     * 再经 {@link KnowledgeVectorIngester} 按类型增量灌库；若指纹与侧车一致且向量快照已存在则整段跳过。
     * 仅当本次对向量库有删改时才持久化索引（仅对 {@link SimpleVectorStore} 生效）；成功后写入知识源指纹供下次短路。
     */
    public void importDocs() {
        String knowledgeType = storageProperties.getKnowledge().getType();
        log.info(">>>> [RAG-Core] 策略总线点火。当前运行模式 = [{}]", knowledgeType);

        String bizCategory = storageProperties.getKnowledge().getDefaultBizCategory();
        Optional<String> fpOpt = knowledgeRevisionFingerprinter.fingerprint(knowledgeType, bizCategory);
        Path vectorIndex = VectorStoragePaths.resolveVectorIndexFile(storageProperties);
        if (fpOpt.isPresent()
                && vectorIndex != null
                && Files.isRegularFile(vectorIndex)
                && fpOpt.get().equals(ragIngestionSidecar.getLastSourceFingerprint())) {
            log.info(">>>> [RAG-ETL] 知识源指纹未变且向量快照存在，跳过本次 importDocs（无读源、无判重 embedding）");
            return;
        }

        boolean vectorStoreMutated = false;
        try {
            List<Document> documents = knowledgeImportPipeline.loadDocuments(knowledgeType, bizCategory);

            if (documents == null || documents.isEmpty()) {
                log.warn(">>>> [RAG-ETL] 提取出的有效 Document 记录为空，跳过本次灌注。");
                return;
            }

            vectorStoreMutated = knowledgeVectorIngester.ingest(knowledgeType, documents);

            fpOpt.ifPresent(fp -> ragIngestionSidecar.setFingerprintAfterSuccessfulImport(fp, knowledgeType, bizCategory));

        } finally {
            if (vectorStoreMutated) {
                saveIndex();
            } else {
                log.debug(">>>> [RAG-ETL] 向量库无变更，跳过 SimpleVectorStore 落盘");
            }
        }
    }

    /**
     * 清理已导入的向量数据。
     * <p>
     * - 当使用 {@link SimpleVectorStore} 时，删除本地持久化的 JSON 索引文件（相当于清空）。<br>
     * - 其他 VectorStore 实现通常需要按 ID 或 filter 删除，这里只做提示不做强行清空。
     */
    public void deleteDocs() {
        if (vectorStore instanceof SimpleVectorStore) {
            deleteSimpleVectorStoreFiles();
            return;
        }

        log.warn(
                ">>>> [RAG-ETL] 当前 VectorStore 实现（{}）不支持无条件清空。请提供要删除的 Document ID 列表再调用 vectorStore.delete(idList)。",
                vectorStore.getClass().getName());
    }

    /**
     * 将 {@link SimpleVectorStore} 的内存索引持久化到本地文件（{@code app.storage.vector-db}）。
     * <p>
     * 如果当前 VectorStore 不是 SimpleVectorStore，则直接忽略（因为不一定支持 save）。
     * 持久化失败不会中断导入流程，只会打印错误日志。
     */
    private void saveIndex() {
        if (!(vectorStore instanceof SimpleVectorStore simpleVectorStore)) {
            return;
        }

        Path target = VectorStoragePaths.resolveVectorIndexFile(storageProperties);
        if (target == null) {
            log.warn(">>>> [RAG-ETL] 未配置 app.storage.vector-db，已跳过保存 SimpleVectorStore 索引");
            return;
        }

        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            simpleVectorStore.save(new File(target.toString()));
            log.info(">>>> [RAG-ETL] SimpleVectorStore：索引已持久化到 {}", target.toAbsolutePath());
        } catch (Exception e) {
            log.error(">>>> [RAG-ETL] SimpleVectorStore：索引持久化失败（不会中断任务），目标路径={}", target.toAbsolutePath(), e);
        }
    }

    /**
     * 删除 {@link SimpleVectorStore} 的本地持久化文件（{@code app.storage.vector-db}
     * 指向的文件或目录下的 *.json）。
     * <p>
     * 用于“清空”本地向量库；若路径是目录，会删除目录下所有 JSON 文件。
     */
    private void deleteSimpleVectorStoreFiles() {
        String vectorDb = storageProperties.getStorage().getVectorDb();
        if (vectorDb == null || vectorDb.isBlank()) {
            log.warn(">>>> [RAG-ETL] 未配置 app.storage.vector-db，无法定位 SimpleVectorStore 的本地 JSON 文件");
            return;
        }

        Path configured = Path.of(vectorDb);
        Path index = VectorStoragePaths.resolveVectorIndexFile(storageProperties);
        if (index == null) {
            return;
        }
        try {
            if (Files.isDirectory(configured)) {
                int deleted = 0;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(configured, "*.json")) {
                    for (Path f : ds) {
                        if (Files.deleteIfExists(f)) {
                            deleted++;
                        }
                    }
                }
                log.info(">>>> [RAG-ETL] SimpleVectorStore：已删除 {} 个本地向量 JSON 文件（目录：{}）", deleted, configured.toAbsolutePath());
            } else {
                boolean ok = Files.deleteIfExists(index);
                if (ok) {
                    log.info(">>>> [RAG-ETL] SimpleVectorStore：已删除本地向量 JSON 文件：{}", index.toAbsolutePath());
                } else {
                    log.info(">>>> [RAG-ETL] SimpleVectorStore：未发现本地向量 JSON 文件：{}", index.toAbsolutePath());
                }
            }
            ragIngestionSidecar.deleteSidecarFileIfExists();
        } catch (IOException e) {
            throw new IllegalStateException("删除 SimpleVectorStore 本地 JSON 文件失败: " + index.toAbsolutePath(), e);
        }
    }

    /**
     * 分区检索：按业务分类（biz_category）过滤后做相似度检索。
     * <p>
     * WiseLink 分身检索：通过 {@link WiseLinkMultiQueryExpander} 将用户问题改写为多路检索词，并行检索后按文档 ID
     * 去重合并。
     */
    public List<Document> similaritySearch(String query, String bizCategory) {
        log.info(">>>> [RAG-Search] 正在执行分区检索（WiseLink 分身检索），分类：{}。", bizCategory);
        Filter.Expression exp = new FilterExpressionBuilder().eq("biz_category", bizCategory).build();
        List<Document> results = multiQuerySimilaritySearch(Objects.requireNonNull(query), exp);
        logNoResultsIfEmpty(results);
        return results;
    }

    /**
     * 普通检索：不使用 metadata 过滤器。
     * <p>
     * 同样走 WiseLink 分身检索流程（3 路并行 + 去重）。
     */
    public List<Document> similaritySearch(String query) {
        List<Document> results = multiQuerySimilaritySearch(Objects.requireNonNull(query), null);
        logNoResultsIfEmpty(results);
        return results;
    }

    /**
     * MultiQueryExpander（WiseLink 分身检索）：将 query 扩展为多路检索词，并行做向量检索并去重。
     */
    private List<Document> multiQuerySimilaritySearch(String query, Filter.Expression filterExpression) {
        Query base = Query.builder()
                .text(Objects.requireNonNullElse(query, ""))
                .history(List.of())
                .context(Map.of())
                .build();
        List<Query> expanded = wiseLinkMultiQueryExpander.expand(base);
        if (expanded.size() == 1) {
            return executeSimilaritySearch(expanded.getFirst().text(), filterExpression);
        }

        List<CompletableFuture<List<Document>>> futureList = expanded.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> executeSimilaritySearch(q.text(), filterExpression)))
                .toList();
        CompletableFuture.allOf(futureList.toArray(CompletableFuture<?>[]::new)).join();

        List<Document> merged = new ArrayList<>();
        for (CompletableFuture<List<Document>> f : futureList) {
            List<Document> batch = f.join();
            if (batch != null) {
                merged.addAll(batch);
            }
        }
        return dedupeDocumentsById(merged);
    }

    private List<Document> executeSimilaritySearch(String q, Filter.Expression filterExpression) {
        SearchRequest.Builder b = SearchRequest.builder()
                .query(Objects.requireNonNullElse(q, ""))
                .topK(storageProperties.getStorage().getRagTopK())
                .similarityThreshold(storageProperties.getStorage().getSimilarityThreshold());
        if (filterExpression != null) {
            b.filterExpression(filterExpression);
        }
        return vectorStore.similaritySearch(b.build());
    }

    private static List<Document> dedupeDocumentsById(List<Document> documents) {
        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            String id = doc.getId();
            String key = id != null && !id.isBlank() ? id : "noid-" + System.identityHashCode(doc);
            byId.putIfAbsent(key, doc);
        }
        return new ArrayList<>(byId.values());
    }

    private void logNoResultsIfEmpty(List<Document> results) {
        if (results != null && !results.isEmpty()) {
            return;
        }
        double similarityThreshold = storageProperties.getStorage().getSimilarityThreshold();
        // 按你的要求：阈值为 0.5 时输出固定文案，便于检索与对齐。
        log.info(">>>> [RAG-Search] 未找到相似度大于 {} 的知识片段。", similarityThreshold);
    }
}
