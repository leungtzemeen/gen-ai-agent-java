package com.gen.ai.wiselink.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.gen.ai.wiselink.annotation.WiseLinkTool;

import cn.hutool.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * WiseLink 2.0 系统管理类工具：选购意向持久化（由 {@link com.gen.ai.wiselink.registry.WiseLinkToolRegistry} 扫描注册）。
 */
@Service
@Slf4j
public class WiseLinkSystemToolsService {

    private static final String DATA_SUBDIR = "data";
    private static final String INTENT_LOG = "shopping_intent.log";

    /** 文件锁重试：避免高并发下长时间阻塞调用线程。 */
    private static final int INTENT_LOG_LOCK_MAX_ATTEMPTS = 25;

    private static final long INTENT_LOG_LOCK_RETRY_MS = 40L;

    public record RecordUserInterestRequest(String userId, String productName, String userCoreRequirement) {
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
}
