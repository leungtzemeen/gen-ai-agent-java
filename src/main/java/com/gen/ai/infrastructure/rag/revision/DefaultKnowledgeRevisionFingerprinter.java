package com.gen.ai.infrastructure.rag.revision;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.gen.ai.config.StorageProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON / Markdown 基于本地文件的指纹；{@code mysql} 返回空（短路由上层忽略）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultKnowledgeRevisionFingerprinter implements KnowledgeRevisionFingerprinter {

    private final StorageProperties storageProperties;

    @Override
    public Optional<String> fingerprint(String knowledgeType, String bizCategory) {
        if (knowledgeType == null || knowledgeType.isBlank()) {
            return Optional.empty();
        }
        String t = knowledgeType.toLowerCase(Locale.ROOT);
        String cat = bizCategory != null ? bizCategory : "";
        return switch (t) {
            case "json" -> Optional.of("json:" + cat + ":" + hex(digestFile(jsonKnowledgePath())));
            case "markdown" -> Optional.of("markdown:" + cat + ":" + hex(markdownTreeDigest()));
            case "mysql" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private Path jsonKnowledgePath() {
        String rag = storageProperties.getStorage().getRagDocs();
        return Path.of(rag, "goods_knowledge_base.json");
    }

    private Path markdownRoot() {
        return Path.of(storageProperties.getStorage().getRagDocs());
    }

    /** 目录下所有 .md 的路径 + 最后修改时间 + 长度，排序后做 SHA-256。 */
    private byte[] markdownTreeDigest() {
        Path root = markdownRoot();
        if (!Files.isDirectory(root)) {
            return new byte[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEachOrdered(p -> {
                            try {
                                md.update(p.toString().getBytes(StandardCharsets.UTF_8));
                                md.update((byte) 0);
                                md.update(String.valueOf(Files.getLastModifiedTime(p).toMillis()).getBytes(StandardCharsets.UTF_8));
                                md.update((byte) 0);
                                md.update(String.valueOf(Files.size(p)).getBytes(StandardCharsets.UTF_8));
                                md.update((byte) '\n');
                            } catch (Exception e) {
                                log.debug("指纹扫描跳过文件 {}: {}", p, e.getMessage());
                            }
                        });
            }
            return md.digest();
        } catch (Exception e) {
            log.warn(">>>> [RAG-Fingerprint] Markdown 目录指纹失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    private static byte[] digestFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new byte[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            }
            return md.digest();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
