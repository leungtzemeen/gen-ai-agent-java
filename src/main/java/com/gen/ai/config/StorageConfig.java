package com.gen.ai.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gen.ai.memory.FileChatMemoryRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
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
    public ChatMemoryRepository fileChatMemory() {
        log.info(">>>> [记忆系统] 已挂载本地文件存储 (Kryo版)");
        return new FileChatMemoryRepository(storageProperties);
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        log.info(">>>> [记忆系统] 已挂载 10 条消息窗口记忆管理");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10) // 💡 逻辑层：在这里控制记忆长度
                .build();
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
