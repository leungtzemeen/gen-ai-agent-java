package com.gen.ai.infrastructure.rag;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.PathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.gen.ai.config.StorageProperties;
import com.gen.ai.rag.WiseLinkMultiQueryExpander;

import lombok.extern.slf4j.Slf4j;

/**
 * RAG 数据管线：Markdown 增量导入、向量索引持久化/清空、按品类过滤与 WiseLink 多查询并行检索。
 */
@Service
@Slf4j
public class RagDataService {

    private final VectorStore vectorStore;
    private final StorageProperties storageProperties;
    private final QueryExpander wiseLinkMultiQueryExpander;

    public RagDataService(
            VectorStore vectorStore,
            StorageProperties storageProperties,
            WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander) {
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.storageProperties = Objects.requireNonNull(storageProperties);
        this.wiseLinkMultiQueryExpander = Objects.requireNonNull(wiseLinkMultiQueryExpander);
    }

    /**
     * RAG 相似度阈值（越大越严格）。
     * <p>
     * 可通过 yml 配置 {@code app.rag.similarity-threshold} 覆盖，默认 0.5。
     */
    @Value("${app.rag.similarity-threshold:0.5}")
    private double similarityThreshold;

    /**
     * 从配置的知识库目录（{@code app.storage.rag-docs}）批量导入 Markdown 文档到向量库。
     * <p>
     * 处理流程：遍历目录下所有 {@code .md} 文件 → 计算文件哈希做增量去重 → 删除同名旧数据 → 切片（含 overlap）
     * → 补充业务元数据 → 向量化写入 {@link VectorStore}。
     * <p>
     * 无论中途是否有文件失败，都会在结束时尝试持久化索引（仅对 {@link SimpleVectorStore} 生效）。
     */
    public void importDocs() {
        String ragDocsDir = storageProperties.getRagDocs();
        if (ragDocsDir == null || ragDocsDir.isBlank()) {
            log.warn(">>>> [RAG-ETL] 未配置 app.storage.rag-docs，已跳过导入");
            return;
        }

        Path root = Path.of(ragDocsDir);
        if (!Files.exists(root)) {
            log.warn(">>>> [RAG-ETL] 知识库目录不存在：{}，已跳过导入", root);
            return;
        }

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .build();

        try {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                        .sorted()
                        .forEach(path -> processSingleFileIncrementally(path, splitter));
            } catch (IOException e) {
                throw new IllegalStateException("遍历 RAG 文档目录失败: " + root, e);
            }
        } finally {
            // 无论某个文件导入是否失败，都尽量做一次落盘（尤其是 SimpleVectorStore）
            saveIndex();
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

        String vectorDb = storageProperties.getVectorDb();
        if (vectorDb == null || vectorDb.isBlank()) {
            log.warn(">>>> [RAG-ETL] 未配置 app.storage.vector-db，已跳过保存 SimpleVectorStore 索引");
            return;
        }

        Path configured = Path.of(vectorDb);
        Path target = Files.isDirectory(configured) ? configured.resolve("vector-store.json") : configured;

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
     * 删除 {@link SimpleVectorStore} 的本地持久化文件（{@code app.storage.vector-db} 指向的文件或目录下的 *.json）。
     * <p>
     * 用于“清空”本地向量库；若路径是目录，会删除目录下所有 JSON 文件。
     */
    private void deleteSimpleVectorStoreFiles() {
        String vectorDb = storageProperties.getVectorDb();
        if (vectorDb == null || vectorDb.isBlank()) {
            log.warn(">>>> [RAG-ETL] 未配置 app.storage.vector-db，无法定位 SimpleVectorStore 的本地 JSON 文件");
            return;
        }

        Path p = Path.of(vectorDb);
        try {
            if (Files.isDirectory(p)) {
                int deleted = 0;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.json")) {
                    for (Path f : ds) {
                        if (Files.deleteIfExists(f)) {
                            deleted++;
                        }
                    }
                }
                log.info(">>>> [RAG-ETL] SimpleVectorStore：已删除 {} 个本地向量 JSON 文件（目录：{}）", deleted, p.toAbsolutePath());
                return;
            }

            boolean ok = Files.deleteIfExists(p);
            if (ok) {
                log.info(">>>> [RAG-ETL] SimpleVectorStore：已删除本地向量 JSON 文件：{}", p.toAbsolutePath());
            } else {
                log.info(">>>> [RAG-ETL] SimpleVectorStore：未发现本地向量 JSON 文件：{}", p.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除 SimpleVectorStore 本地 JSON 文件失败: " + p.toAbsolutePath(), e);
        }
    }

    /**
     * 对单个 Markdown 文件执行增量导入。
     * <p>
     * - 计算 MD5 并基于 metadata(source + file_hash) 判断是否已入库，未变更则跳过<br>
     * - 若变更：先删除同名旧数据，再切片/加 overlap/补业务元数据后写入向量库
     * <p>
     * 任何异常都会被捕获并记录日志，避免影响后续文件导入。
     */
    private void processSingleFileIncrementally(Path path, TokenTextSplitter splitter) {
        String filename = path.getFileName().toString();

        try {
            String currentHash = computeMd5(path);

            if (hasSameSourceAndHash(filename, currentHash)) {
                log.info("文件未变动，跳过导入：{}", filename);
                return;
            }

            // 先删除该文件名的旧数据（如果存在）
            deleteBySource(filename);

            List<Document> splitDocuments = applyOverlap(split(path, filename, currentHash, splitter), 50);
            enhanceBusinessMetadata(splitDocuments, filename);
            log.info("正在向量化文件：{}", filename);
            vectorStore.accept(splitDocuments);
            log.info("成功存入 {} 个知识切片", splitDocuments.size());
        } catch (Exception e) {
            log.error(">>>> [RAG-ETL] 导入文件失败（已跳过）：{}", filename, e);
        }
    }

    /**
     * 业务增强：在保留 Spring AI 自动元数据（如 title, chunk_index 等）的前提下，
     * 注入业务分类与入库时间，用于后续向量检索的 metadata 过滤。
     */
    private static void enhanceBusinessMetadata(List<Document> docs, String filename) {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        String bizCategory = inferBizCategoryFromFilename(filename);
        String createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> enhancements = new HashMap<>();
        enhancements.put("biz_category", bizCategory);
        enhancements.put("created_at", createdAt);

        // 保护原有元数据：保留 title, chunk_index 等，只添加/覆盖我们的字段
        docs.forEach(doc -> doc.getMetadata().putAll(enhancements));
    }

    /**
     * 根据文件名推断业务分类（写入 metadata 的 {@code biz_category}），用于后续按分类检索过滤。
     * <p>
     * 当前规则为硬编码关键字匹配，未命中时返回“未分类”。
     */
    private static String inferBizCategoryFromFilename(String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.contains("audio-visual")) {
            return "影音导购";
        }
        if (name.contains("cleaning")) {
            return "家电清洗";
        }
        if (name.contains("health")) {
            return "运动健康";
        }
        return "未分类";
    }

    /**
     * 判断向量库中是否已存在“同一来源文件 + 同一文件哈希”的记录，用于增量去重。
     * <p>
     * 通过 metadata 过滤：{@code source == filename AND file_hash == fileHash}，只取 1 条命中即可。
     */
    private boolean hasSameSourceAndHash(String filename, String fileHash) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression exp = b.and(
                b.eq("source", filename),
                b.eq("file_hash", fileHash)).build();

        // 这里只做“是否存在”的去重判定，因此只取 1 条即可。
        List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
                .query(Objects.requireNonNull(filename))
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(Objects.requireNonNull(exp))
                .build());

        return matches != null && !matches.isEmpty();
    }

    /**
     * 删除指定来源文件（metadata 的 {@code source}）对应的全部切片数据。
     * <p>
     * 用于文件更新时先清理旧版本，再写入新版本。
     */
    private void deleteBySource(String filename) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression exp = b.eq("source", filename).build();
        vectorStore.delete(Objects.requireNonNull(exp));
    }

    /**
     * 分区检索：按业务分类（biz_category）过滤后做相似度检索。
     * <p>
     * WiseLink 分身检索：通过 {@link WiseLinkMultiQueryExpander} 将用户问题改写为多路检索词，并行检索后按文档 ID 去重合并。
     */
    public List<Document> similaritySearch(String query, String bizCategory) {
        log.info(">>>> [RAG-Search] 正在执行分区检索（WiseLink 分身检索），分类：{}。", bizCategory);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression exp = b.eq("biz_category", bizCategory).build();
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
                .topK(5)
                .similarityThreshold(similarityThreshold);
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
        // 按你的要求：阈值为 0.5 时输出固定文案，便于检索与对齐。
        if (Double.compare(similarityThreshold, 0.5d) == 0) {
            log.info(">>>> [RAG-Search] 未找到相似度大于 0.5 的知识片段。");
        } else {
            log.info(">>>> [RAG-Search] 未找到相似度大于 {} 的知识片段。", similarityThreshold);
        }
    }

    /**
     * 计算文件内容的 MD5（十六进制字符串），用于增量导入时判断文件是否变更。
     */
    private static String computeMd5(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return DigestUtils.md5DigestAsHex(Objects.requireNonNull(in));
        } catch (IOException e) {
            throw new IllegalStateException("计算文件 MD5 失败: " + path.toAbsolutePath(), e);
        }
    }

    /**
     * 读取 Markdown 文件为 {@link Document}，并用 {@link TokenTextSplitter} 切片。
     * <p>
     * 会把 {@code source} 与 {@code file_hash} 写入 Document metadata，供去重/删除/过滤检索使用。
     */
    private static List<Document> split(Path path, String filename, String fileHash, TokenTextSplitter splitter) {
        // String filePath = path.toAbsolutePath().toString();
        // Path resourcePath = Objects.requireNonNull(Path.of(Objects.requireNonNull(filePath)));
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("source", filename)
                .withAdditionalMetadata("file_hash", fileHash)
                .build();
        // MarkdownDocumentReader reader = new MarkdownDocumentReader(newPathResource(resourcePath), config);
        // 直接用传入的 path 即可，更简洁
        MarkdownDocumentReader reader = new MarkdownDocumentReader(new PathResource(Objects.requireNonNull(path)), config);

        List<Document> documents = reader.get();
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return splitter.apply(documents);
    }

    /**
     * Spring AI 1.1.x 的 {@link TokenTextSplitter} 暂不支持 overlap，这里用“按空白分词”的近似方式实现
     * chunkOverlap=50：将上一段末尾 50 个词拼接到下一段开头。
     */
    private static List<Document> applyOverlap(List<Document> docs, int overlapTerms) {
        if (docs == null || docs.size() <= 1 || overlapTerms <= 0) {
            return docs == null ? List.of() : docs;
        }

        List<Document> out = new ArrayList<>(docs.size());
        String prevText = null;

        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                out.add(doc);
                continue;
            }

            if (prevText != null && !prevText.isBlank()) {
                String overlap = tailTerms(prevText, overlapTerms);
                if (!overlap.isBlank()) {
                    text = overlap + System.lineSeparator() + text;
                }
            }

            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            out.add(Document.builder().text(text).metadata(metadata).build());
            prevText = doc.getText();
        }

        return out;
    }

    /**
     * 从上一段文本末尾提取 N 个“词”（按空白分割）作为 overlap。
     * <p>
     * 对无空格文本（如纯中文）做兜底：取末尾 N 个字符。
     */
    private static String tailTerms(String text, int terms) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < terms) {
            // 对纯中文等“无空格”的场景兜底：直接截取末尾 N 个字符作为 overlap。
            int n = Math.min(terms, trimmed.length());
            return trimmed.substring(trimmed.length() - n);
        }
        int start = parts.length - terms;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}

