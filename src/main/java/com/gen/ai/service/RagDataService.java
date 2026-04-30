package com.gen.ai.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.PathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.gen.ai.config.StorageProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagDataService {

    private final VectorStore vectorStore;
    private final StorageProperties storageProperties;

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

    public void deleteDocs() {
        if (vectorStore instanceof SimpleVectorStore) {
            deleteSimpleVectorStoreFiles();
            return;
        }

        log.warn(
                ">>>> [RAG-ETL] 当前 VectorStore 实现（{}）不支持无条件清空。请提供要删除的 Document ID 列表再调用 vectorStore.delete(idList)。",
                vectorStore.getClass().getName());
    }

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
            log.info("正在向量化文件：{}", filename);
            vectorStore.accept(splitDocuments);
            log.info("成功存入 {} 个知识切片", splitDocuments.size());
        } catch (Exception e) {
            log.error(">>>> [RAG-ETL] 导入文件失败（已跳过）：{}", filename, e);
        }
    }

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

    private void deleteBySource(String filename) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression exp = b.eq("source", filename).build();
        vectorStore.delete(Objects.requireNonNull(exp));
    }

    private static String computeMd5(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return DigestUtils.md5DigestAsHex(Objects.requireNonNull(in));
        } catch (IOException e) {
            throw new IllegalStateException("计算文件 MD5 失败: " + path.toAbsolutePath(), e);
        }
    }

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

