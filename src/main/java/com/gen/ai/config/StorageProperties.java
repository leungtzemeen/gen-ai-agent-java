package com.gen.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * Root directory for all app storage.
     * Example: ${user.home}/data/gen-ai-agent
     */
    private String root;

    /**
     * Directory for chat history persistence.
     * Example: ${app.storage.root}/history
     */
    private String chatHistory;

    /**
     * Directory for RAG documents.
     * Example: ${app.storage.root}/knowledge
     */
    private String ragDocs;

    /**
     * Directory for vector store persistence.
     * Example: ${app.storage.root}/vector-store
     */
    private String vectorDb;
}

