package com.gen.ai.service;

import org.springframework.ai.reader.TextReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.gen.ai.config.StorageProperties;

@Service
public class RagDataService {
    
    @Autowired
    private StorageProperties storageProperties;

    public void loadDocs() {
        // 使用 FileSystemResource 包装路径，保持 Spring AI 的 API 对接一致性
        Resource resource = new FileSystemResource(storageProperties.getRagDocs() + "/tea-info.md");
        // 交给 Spring AI 的 TextReader 处理
        TextReader reader = new TextReader(resource);
        // ... ETL 逻辑
    }
}

