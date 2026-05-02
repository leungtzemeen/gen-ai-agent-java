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
import com.gen.ai.rag.ShortCircuitCompressionQueryTransformer;
import com.gen.ai.rag.WiseLinkMultiQueryExpander;

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
            @Value("${app.rag.similarity-threshold:0.5}") double similarityThreshold) {

        CompressionQueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(promptBundle.compressionQueryTransformTemplate())
                .build();
        ShortCircuitCompressionQueryTransformer compressionScoped = new ShortCircuitCompressionQueryTransformer(compression);

        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(similarityThreshold)
                .topK(5)
                .build();

        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(promptBundle.contextualQueryAugmentTemplate())
                .allowEmptyContext(true)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(compressionScoped)
                .queryExpander(wiseLinkMultiQueryExpander)
                .documentRetriever(retriever)
                .queryAugmenter(augmenter)
                .build();
    }
}
