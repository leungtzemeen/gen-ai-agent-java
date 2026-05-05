package com.gen.ai.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gen.ai.infrastructure.memory.FileChatMemoryRepository;
import com.gen.ai.infrastructure.memory.WiseLinkChatMemoryConversationFilter;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地存储与向量库 Bean：会话记忆仓库、滑动窗口记忆、{@link SimpleVectorStore} 持久化加载。
 */
@Configuration
@EnableConfigurationProperties({ StorageProperties.class, AppSecurityProperties.class })
@RequiredArgsConstructor
@Slf4j
public class StorageConfig {

    private final StorageProperties storageProperties;

    @PostConstruct
    public void init() {
        log.info(">>>> [存储系统] 正在初始化文件目录...");
        createDirIfConfigured(storageProperties.getChatHistory());
        createDirIfConfigured(storageProperties.getRagDocs());
    }

    @Bean
    public ChatMemoryRepository fileChatMemory(WiseLinkChatMemoryConversationFilter wiseLinkChatMemoryConversationFilter) {
        log.info(">>>> [记忆系统] 已挂载本地文件存储 (Kryo版) + WiseLink 会话净化；单会话读回窗口 lastN=6");
        return new FileChatMemoryRepository(storageProperties, 6, wiseLinkChatMemoryConversationFilter);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        log.info(">>>> [记忆系统] 已挂载 6 条消息滑动窗口（约 3 轮问-答）");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(6)
                .build();
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        // 这是一个本地文件版的向量数据库，它会把索引存在你配置的 vector-db 路径下
        String vectorDbPath = storageProperties.getVectorDb();
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();

        File vectorFile = new File(vectorDbPath);   
        if (vectorFile.exists()) {
            // 启动时自动加载之前存好的知识
            vectorStore.load(vectorFile);
        }
        return vectorStore;
    }

    private static void createDirIfConfigured(String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化存储目录: " + dir, e);
        }
    }
}
