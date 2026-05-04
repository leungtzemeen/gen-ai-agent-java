package com.gen.ai.wiselink.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.gen.ai.wiselink.annotation.WiseLinkTool;
import com.gen.ai.wiselink.security.WiseLinkToolSecurityInterceptor;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 2.0 系统管理类工具：意向持久化与受控资料下载（由 {@link com.gen.ai.wiselink.registry.WiseLinkToolRegistry} 扫描注册）。
 */
@Service
@Slf4j
public class WiseLinkSystemToolsService {

    private static final String DATA_SUBDIR = "data";
    private static final String INTENT_LOG = "shopping_intent.log";
    private static final String DOWNLOADS_SUBDIR = "downloads";
    private static final int DOWNLOAD_TIMEOUT_MS = 120_000;

    /** 文件锁重试：避免高并发下长时间阻塞调用线程。 */
    private static final int INTENT_LOG_LOCK_MAX_ATTEMPTS = 25;

    private static final long INTENT_LOG_LOCK_RETRY_MS = 40L;

    public record RecordUserInterestRequest(String userId, String productName, String userCoreRequirement) {
    }

    public record ExpertGuideDownloadRequest(String fileUrl, String fileName) {
    }

    @WiseLinkTool(
            name = "recordUserInterest",
            enabled = false,
            description = "将用户当前的选购意向以 JSON Lines（每行一条完整 JSON）追加写入本地日志，便于后续会话或运营侧跟进。"
                    + "当用户明确表达购买意愿或要求你记住某个选择时，请调用此工具进行持久化记录。"
                    + "请传入用户标识 userId（可为会话 ID、会员号等业务侧 ID）、商品名称 productName、核心诉求摘要 userCoreRequirement。"
                    + "写入过程使用文件锁与重试，避免并发撕裂。")
    public String recordUserInterest(RecordUserInterestRequest request) {
        try {
            String uid = request == null || request.userId() == null ? "" : request.userId().trim();
            String product =
                    request == null || request.productName() == null ? "" : request.productName().trim();
            String requirement = request == null || request.userCoreRequirement() == null
                    ? ""
                    : request.userCoreRequirement().trim();
            if (uid.isEmpty()) {
                return "错误：recordUserInterest 需要非空的 userId（用户或会话标识）。";
            }
            if (product.isEmpty()) {
                return "错误：recordUserInterest 需要非空的 productName。";
            }
            if (requirement.isEmpty()) {
                return "错误：recordUserInterest 需要非空的 userCoreRequirement（用户核心诉求摘要）。";
            }

            Path root = Paths.get(System.getProperty("user.dir", ".")).normalize();
            Path dataDir = root.resolve(DATA_SUBDIR);
            Files.createDirectories(dataDir);
            Path logPath = dataDir.resolve(INTENT_LOG);

            JSONObject line = new JSONObject(true);
            line.set("timestamp", Instant.now().toString());
            line.set("userId", uid);
            line.set("productName", product);
            line.set("userCoreRequirement", requirement);

            String jsonLine = line.toString() + '\n';
            byte[] bytes = jsonLine.getBytes(StandardCharsets.UTF_8);

            appendJsonLineWithSharedRetry(logPath, bytes);

            Path absolute = logPath.toAbsolutePath().normalize();
            log.info(">>>> [WiseLink-System] 已记录选购意向 userId='{}' product='{}' -> {}", uid, product, absolute);
            return "已持久化选购意向（JSON Lines + 文件锁追加一行）。日志文件：" + absolute;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn(">>>> [WiseLink-System] recordUserInterest 等待锁被中断: {}", ex.toString());
            return "记录选购意向被中断（对话可继续），请稍后重试。";
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-System] recordUserInterest 失败: {}", ex.toString());
            return "记录选购意向失败（对话可继续）：" + ex.getMessage();
        }
    }

    /**
     * 在独占 {@link FileLock} 保护下一次性写入整行字节，避免半行撕裂；获取锁失败时短暂休眠并重试，超过次数则失败返回。
     * 最后一轮尝试前 {@link Thread#yield()}，让刚释放锁的写入方有机会被调度，稳中求快。
     */
    private static void appendJsonLineWithSharedRetry(Path logPath, byte[] lineUtf8) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= INTENT_LOG_LOCK_MAX_ATTEMPTS; attempt++) {
            if (attempt == INTENT_LOG_LOCK_MAX_ATTEMPTS) {
                Thread.yield();
            }
            ByteBuffer buffer = ByteBuffer.wrap(lineUtf8);
            try (FileChannel channel =
                    FileChannel.open(logPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                FileLock candidate = channel.tryLock();
                if (candidate == null) {
                    if (attempt >= INTENT_LOG_LOCK_MAX_ATTEMPTS) {
                        log.warn(
                                ">>>> [WiseLink-System] shopping_intent.log 获取独占锁超时，已重试 {} 次",
                                INTENT_LOG_LOCK_MAX_ATTEMPTS);
                        throw new IOException(
                                "无法在时限内获取日志文件锁（高并发写入），请稍后重试；若持续失败请检查是否有其它进程长期占用 "
                                        + INTENT_LOG);
                    }
                    Thread.sleep(INTENT_LOG_LOCK_RETRY_MS);
                    continue;
                }
                final FileLock exclusiveLock = candidate;
                try {
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    return;
                } finally {
                    exclusiveLock.release();
                }
            }
        }
    }

    @WiseLinkTool(
            name = "downloadExpertGuide",
            vipOnly = true,
            description = "从用户提供的公开 HTTP(S) 链接下载资料到本地 downloads 目录，用于保存说明书、压缩包或演示视频等附件。"
                    + "仅允许安全的文件后缀：.pdf、.zip、.mp4。"
                    + "在已通过 exportShoppingReport 生成 PDF 选购报告后，若用户还需要下载相关产品高清画质演示片源或更详细的说明书/附件包，请主动引导其发起下载请求并调用本工具完成落地。"
                    + "成功后请将返回的本地路径告知用户。"
                    + WiseLinkToolSecurityInterceptor.TOOL_DESCRIPTION_SECURITY_NOTICE)
    public String downloadExpertGuide(ExpertGuideDownloadRequest request) {
        try {
            String rawUrl = request == null || request.fileUrl() == null ? "" : request.fileUrl().trim();
            String rawName = request == null || request.fileName() == null ? "" : request.fileName().trim();
            if (rawUrl.isEmpty()) {
                return "错误：downloadExpertGuide 需要非空的 fileUrl（http/https）。";
            }

            URI uri;
            try {
                uri = URI.create(rawUrl);
            } catch (IllegalArgumentException ex) {
                return "错误：URL 无法解析 — " + ex.getMessage();
            }
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return "错误：仅允许 http 或 https 链接。";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "错误：URL 缺少有效主机名。";
            }

            String safeName = validateDownloadFileName(rawName);

            Path root = Paths.get(System.getProperty("user.dir", ".")).normalize();
            Path downloadDir = root.resolve(DOWNLOADS_SUBDIR);
            Files.createDirectories(downloadDir);
            Path target = downloadDir.resolve(safeName).normalize();
            if (!target.startsWith(downloadDir.normalize())) {
                return "错误：解析后的保存路径非法。";
            }

            File dest = target.toFile();
            long sizeBefore = dest.exists() ? dest.length() : -1L;
            HttpUtil.downloadFile(rawUrl, dest, DOWNLOAD_TIMEOUT_MS);
            Path absolute = target.toAbsolutePath().normalize();
            log.info(
                    ">>>> [WiseLink-System] 已下载 url='{}' -> {} (previousSize={})",
                    uri.getHost(),
                    absolute,
                    sizeBefore);
            return "下载成功。文件绝对路径：" + absolute + "（请将该路径告知用户，位于项目 downloads 目录。）";
        } catch (IllegalArgumentException ex) {
            return "下载被拒绝：" + ex.getMessage();
        } catch (Exception ex) {
            log.warn(">>>> [WiseLink-System] downloadExpertGuide 失败: {}", ex.toString());
            return "下载失败（对话可继续）：" + ex.getMessage();
        }
    }

    private static String validateDownloadFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空。");
        }
        String name = fileName.trim();
        if (name.contains("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("文件名不能包含路径分隔符或 ..。");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".pdf") || lower.endsWith(".zip") || lower.endsWith(".mp4"))) {
            throw new IllegalArgumentException("仅允许后缀 .pdf、.zip、.mp4。");
        }
        return name;
    }
}
