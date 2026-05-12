package com.gen.ai.infrastructure.rag.extractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import com.gen.ai.infrastructure.rag.ingestion.KnowledgeDuplicateChecker;
import com.gen.ai.infrastructure.rag.model.KnowledgeLocationContext;
import com.gen.ai.infrastructure.rag.model.RagDocumentMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * Markdown 目录遍历 → 切片与元数据；不写入向量库。未变更文件依赖 {@link KnowledgeDuplicateChecker} 跳过；
 * 变更文件由 {@link com.gen.ai.infrastructure.rag.service.RagDataService} 在灌库前按 source 删除旧切片。
 */
@Component
@Slf4j
public class MarkdownKnowledgeExtractor implements KnowledgeExtractor {

    private final KnowledgeDuplicateChecker duplicateChecker;

    public MarkdownKnowledgeExtractor(KnowledgeDuplicateChecker duplicateChecker) {
        this.duplicateChecker = Objects.requireNonNull(duplicateChecker);
    }

    @Override
    public String supportType() {
        return "markdown";
    }

    /**
     * 遍历 RAG 目录下所有 {@code .md} 文件，逐文件计算 hash、按需切片并收集 {@link Document}。
     */
    @Override
    public List<Document> extract(KnowledgeLocationContext context) {
        List<Document> out = new ArrayList<>();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .build();

        try {
            Resource res = context.getFileResource();
            if (res == null) {
                log.warn(">>>> [RAG-ETL] 未找到文件资源，已跳过导入");
                return out;
            }

            Path root = Path.of(res.getURI());

            if (Files.exists(root) && Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                            .sorted()
                            .forEach(path -> collectFileDocuments(path, splitter, out));
                }
            }
        } catch (Exception e) {
            log.error(">>>> [Extractor-MD-Fatal] 遍历本地 Markdown 知识库目录失败！", e);
        }
        return out;
    }

    /** 单文件：若与向量库中 source+hash 一致则跳过；否则读取、切片、补充业务 metadata 后加入 {@code out}。 */
    private void collectFileDocuments(Path path, TokenTextSplitter splitter, List<Document> out) {
        String filename = path.getFileName().toString();
        try {
            String currentHash = computeMd5(path);
            if (duplicateChecker.hasSameSourceAndHash(filename, currentHash)) {
                log.info("文件未变动，跳过导入：{}", filename);
                return;
            }

            List<Document> splitDocuments = applyOverlap(split(path, filename, currentHash, splitter), 50);
            enhanceBusinessMetadata(splitDocuments, filename);
            out.addAll(splitDocuments);
            log.info("已解析待灌库：{}（{} 个切片）", filename, splitDocuments.size());
        } catch (Exception e) {
            log.error(">>>> [RAG-ETL] 解析 Markdown 失败（已跳过）：{}", filename, e);
        }
    }

    /** 为同一文件产生的所有切片统一写入 {@code biz_category}、{@code created_at} 等辅助 metadata。 */
    private static void enhanceBusinessMetadata(List<Document> docs, String filename) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        String bizCategory = inferBizCategoryFromFilename(filename);
        String createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> enhancements = new HashMap<>();
        enhancements.put(RagDocumentMetadata.BIZ_CATEGORY, bizCategory);
        enhancements.put("created_at", createdAt);
        docs.forEach(doc -> doc.getMetadata().putAll(enhancements));
    }

    /** 根据文件名关键字推断业务分类（影音导购、家电清洗等），无匹配则返回「未分类」。 */
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

    /** 在相邻切片之间拼接上一段末尾若干 token/字符，减轻边界截断导致的语义丢失。 */
    private static List<Document> applyOverlap(List<Document> docs, int overlapTerms) {
        if (docs == null || docs.size() <= 1 || overlapTerms <= 0) {
            return docs == null ? List.of() : docs;
        }

        List<Document> result = new ArrayList<>(docs.size());
        String prevText = null;

        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                result.add(doc);
                continue;
            }

            if (prevText != null && !prevText.isBlank()) {
                String overlap = tailTerms(prevText, overlapTerms);
                if (!overlap.isBlank()) {
                    text = overlap + System.lineSeparator() + text;
                }
            }

            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            result.add(Document.builder().text(text).metadata(metadata).build());
            prevText = doc.getText();
        }

        return result;
    }

    /** 取文本末尾 {@code terms} 个空白分隔片段；片段不足时退化为末尾 {@code terms} 个字符。 */
    private static String tailTerms(String text, int terms) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < terms) {
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

    /** 计算文件 MD5 十六进制字符串，作为 {@link RagDocumentMetadata#FILE_HASH}。 */
    private static String computeMd5(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return DigestUtils.md5DigestAsHex(Objects.requireNonNull(in));
        } catch (IOException e) {
            throw new IllegalStateException("计算文件 MD5 失败: " + path.toAbsolutePath(), e);
        }
    }

    /** 使用 Spring AI Markdown 读取器读入文档，附加 source/hash，再经 {@link TokenTextSplitter} 切分。 */
    private static List<Document> split(Path path, String filename, String fileHash, TokenTextSplitter splitter) {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata(RagDocumentMetadata.SOURCE, filename)
                .withAdditionalMetadata(RagDocumentMetadata.FILE_HASH, fileHash)
                .build();
        MarkdownDocumentReader reader = new MarkdownDocumentReader(new PathResource(Objects.requireNonNull(path)),
                config);

        List<Document> documents = reader.get();
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return splitter.apply(documents);
    }
}
