package com.gen.ai.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "app") // 100% 物理统治 YML 全量资产树
public class StorageProperties {

    private StorageNode storage = new StorageNode();
    private DatasourceNode datasource = new DatasourceNode();
    private KnowledgeNode knowledge = new KnowledgeNode();
    private SecurityNode security = new SecurityNode();

    @Data
    public static class StorageNode {
        private String root;
        private String chatHistory;
        private String ragDocs;
        private String vectorDb;
        /** 高德 map-server MCP（stdio）工作目录；须与 {@code spring.ai.mcp.client.stdio.connections.map-server.args} 末项一致。 */
        private String mcpMapSandbox;
        private int maxMessages = 8;
        private int lastNHistory = 12;
        private int maxObservationChars = 8000;
        private int maxToolInvocationsPerRequest = 5;
        private int ragTopK = 2;
        private double similarityThreshold = 0.75; 
    }

    @Data
    public static class DatasourceNode {
        private String type = "json"; 
    }

    @Data
    public static class KnowledgeNode {
        private String type = "json";
    }

    @Data
    public static class SecurityNode {
        private List<String> shadowWordsBase64 = new ArrayList<>();
    }
}
