package com.gen.ai.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import com.gen.ai.advisor.LoggingDocumentRetriever;
import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.prompt.PromptDefinition;
import com.gen.ai.prompt.WiseLinkPromptRegistry;
import com.gen.ai.infrastructure.rag.query.ShortCircuitCompressionQueryTransformer;
import com.gen.ai.infrastructure.rag.query.WiseLinkMultiQueryExpander;

/**
 * WiseLink Modular RAG：装配 {@link RetrievalAugmentationAdvisor}（压缩改写 → 分身扩展 →
 * 向量检索 → 上下文注入）及人设模板 {@link AssistantGuidePromptBundle}。
 */
@Configuration
public class WiseLinkRagAdvisorConfig {

        @Bean
        AssistantGuidePromptBundle assistantGuidePromptBundle(
                        @Value("classpath:prompts/assistant-guide.st") Resource assistantGuideResource,
                        WiseLinkPromptRegistry registry) throws IOException {
                String full = StreamUtils.copyToString(assistantGuideResource.getInputStream(), StandardCharsets.UTF_8);
                return AssistantGuidePromptBundle.parse(full, registry);
        }

        @Bean
        WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander(ChatClient.Builder chatClientBuilder,
                        WiseLinkPromptRegistry registry) {
                return new WiseLinkMultiQueryExpander(chatClientBuilder, registry);
        }

        @Bean
        // 1. 核心加固：物理锁死装配顺序！强制让 Bundle 先启动、先切片、先灌注注册中心！
        @DependsOn("assistantGuidePromptBundle")
        RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor(
                        AssistantGuidePromptBundle promptBundle,
                        ChatClient.Builder chatClientBuilder,
                        VectorStore vectorStore,
                        WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander,
                        WiseLinkPromptRegistry registry,
                        StorageProperties storageProperties) {

                CompressionQueryTransformer compression = CompressionQueryTransformer.builder()
                                .chatClientBuilder(chatClientBuilder)
                                .promptTemplate(registry.get(PromptDefinition.COMPRESSION_QUERY_TRANSFORM))
                                .build();
                ShortCircuitCompressionQueryTransformer compressionScoped = new ShortCircuitCompressionQueryTransformer(
                                compression);

                VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                                .vectorStore(vectorStore)
                                .similarityThreshold(storageProperties.getStorage().getSimilarityThreshold())
                                .topK(storageProperties.getStorage().getRagTopK())
                                .build();
                DocumentRetriever retrieverWithLog = new LoggingDocumentRetriever(retriever);

                ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                                .promptTemplate(registry.get(PromptDefinition.CONTEXTUAL_QUERY_AUGMENT))
                                .allowEmptyContext(true)
                                .build();

                return RetrievalAugmentationAdvisor.builder()
                                .queryTransformers(compressionScoped)
                                .queryExpander(wiseLinkMultiQueryExpander)
                                .documentRetriever(retrieverWithLog)
                                .queryAugmenter(augmenter)
                                .build();
        }
}
