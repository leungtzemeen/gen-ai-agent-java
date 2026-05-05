package com.gen.ai.infrastructure.memory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.NonNull;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.gen.ai.config.StorageProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于本地目录与 Kryo 序列化的 {@link ChatMemoryRepository} 实现；每个会话对应一个 {@code .kryo} 文件，文件名由 conversationId 经 URL-Safe Base64 编码。
 * <p>
 * 可选 {@link ChatMemoryConversationCleaner}：在合并新消息后写盘前、以及按会话读取时，净化列表（与滑动窗口 {@code maxMessages} 配合）。
 */
@Slf4j
public class FileChatMemoryRepository implements ChatMemoryRepository {

    private final String baseDir;
    private final int lastN;
    private final ChatMemoryConversationCleaner conversationCleaner;
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        // 设置实例化策略
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    public FileChatMemoryRepository(StorageProperties storageProperties) {
        this(storageProperties, 6, ChatMemoryConversationCleaner.NOOP);
    }

    public FileChatMemoryRepository(
            StorageProperties storageProperties, int lastN, ChatMemoryConversationCleaner conversationCleaner) {
        this.lastN = lastN;
        this.conversationCleaner = conversationCleaner != null ? conversationCleaner : ChatMemoryConversationCleaner.NOOP;
        this.baseDir = storageProperties.getChatHistory();
        if (this.baseDir == null || this.baseDir.isBlank()) {
            throw new IllegalStateException("app.storage.chat-history 未配置，无法初始化 FileChatMemory");
        }
        File dir = new File(this.baseDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 chat history 目录: " + this.baseDir);
        }
    }

    @Override
    @NonNull
    public List<String> findConversationIds() {
        File dir = new File(this.baseDir);
        List<String> conversationIds = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".kryo")) {
                    String encoded = file.getName().substring(0, file.getName().length() - ".kryo".length());
                    conversationIds.add(decodeConversationId(encoded));
                }
            }
        }
        return conversationIds;
    }
    
    @Override
    public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
        List<Message> conversationMessages = new ArrayList<>(getOrCreateConversation(conversationId));
        conversationMessages.addAll(messages);
        conversationMessages = conversationCleaner.clean(conversationId, conversationMessages);
        saveConversation(conversationId, conversationMessages);
    }

    @Override
    @NonNull
    public List<Message> findByConversationId(@NonNull String conversationId) {
        List<Message> allMessages = new ArrayList<>(getOrCreateConversation(conversationId));
        allMessages = conversationCleaner.clean(conversationId, allMessages);
        int fromIndex = Math.max(0, allMessages.size() - lastN);
        return new ArrayList<>(allMessages.subList(fromIndex, allMessages.size()));
    }

    @Override
    public void deleteByConversationId(@NonNull String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            if (!file.delete()) {
                log.warn("Failed to delete chat memory file. chatId={}, file={}", conversationId, file.getAbsolutePath());
            }
        }
    }

    @NonNull
    private List<Message> getOrCreateConversation(@NonNull String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                @SuppressWarnings("unchecked")
                List<Message> loaded = (List<Message>) kryo.readObject(input, ArrayList.class);
                return loaded != null ? loaded : new ArrayList<>();
            } catch (IOException e) {
                log.warn("Failed to read chat memory. Returning empty list. chatId={}, file={}",
                        conversationId, file.getAbsolutePath(), e);
            }
        }
        return new ArrayList<>();
    }

    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            log.warn("Failed to write chat memory. chatId={}, file={}", conversationId, file.getAbsolutePath(), e);
        }
    }

    private File getConversationFile(String conversationId) {
        return new File(baseDir, encodeConversationId(conversationId) + ".kryo");
    }

    private static String encodeConversationId(String conversationId) {
        if (conversationId == null) {
            return "null";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeConversationId(String encoded) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encoded;
        }
    }
}
