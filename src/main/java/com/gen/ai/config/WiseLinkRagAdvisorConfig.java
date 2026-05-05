package com.gen.ai.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import com.gen.ai.prompt.AssistantGuidePromptBundle;
import com.gen.ai.rag.RagDocumentTruncatePostProcessor;
import com.gen.ai.rag.ShortCircuitCompressionQueryTransformer;
import com.gen.ai.rag.WiseLinkMultiQueryExpander;

/**
 * WiseLink Modular RAG：装配 {@link RetrievalAugmentationAdvisor}（压缩改写 → 分身扩展 → 向量检索 → 上下文注入）及人设模板 {@link AssistantGuidePromptBundle}。
 * <p>
 * 检索侧压减：{@code topK=2}、{@code app.rag.similarity-threshold}（默认 0.75）、检索后 {@link RagDocumentTruncatePostProcessor} 单片段上限 500 字。
 */
@Configuration
public class WiseLinkRagAdvisorConfig {

    @Bean
    AssistantGuidePromptBundle assistantGuidePromptBundle(
            @Value("classpath:prompts/assistant-guide.st") Resource assistantGuideResource) throws IOException {
        String full = StreamUtils.copyToString(assistantGuideResource.getInputStream(), StandardCharsets.UTF_8);
        return AssistantGuidePromptBundle.parse(full);
    }

    @Bean
    WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander(ChatClient.Builder chatClientBuilder) {
        return new WiseLinkMultiQueryExpander(chatClientBuilder);
    }

    @Bean
    RetrievalAugmentationAdvisor wiseLinkRetrievalAugmentationAdvisor(
            AssistantGuidePromptBundle promptBundle,
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            WiseLinkMultiQueryExpander wiseLinkMultiQueryExpander,
            RagDocumentTruncatePostProcessor ragDocumentTruncatePostProcessor,
            @Value("${app.rag.similarity-threshold:0.75}") double similarityThreshold) {

        CompressionQueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(promptBundle.compressionQueryTransformTemplate())
                .build();
        ShortCircuitCompressionQueryTransformer compressionScoped = new ShortCircuitCompressionQueryTransformer(compression);

        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(similarityThreshold)
                .topK(2)
                .build();

        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(promptBundle.contextualQueryAugmentTemplate())
                .allowEmptyContext(true)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(compressionScoped)
                .queryExpander(wiseLinkMultiQueryExpander)
                .documentRetriever(retriever)
                .documentPostProcessors(ragDocumentTruncatePostProcessor)
                .queryAugmenter(augmenter)
                .build();
    }
}
