package com.devops.kb.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiClient {

    private final WebClient webClient;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.embedding-model}")
    private String embeddingModel;

    @Value("${openai.embedding-dimensions}")
    private int embeddingDimensions;

    @Value("${openai.max-tokens}")
    private int maxTokens;

    @Value("${openai.temperature}")
    private double temperature;

    public OpenAiClient(@Qualifier("openaiWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Generate embedding vector for a single text.
     */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).getFirst();
    }

    /**
     * Batch embedding for multiple texts.
     */
    public List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = Map.of(
                "model", embeddingModel,
                "input", texts,
                "dimensions", embeddingDimensions
        );

        EmbeddingResponse response = webClient.post()
                .uri("/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();

        if (response == null || response.getData() == null) {
            throw new RuntimeException("Empty embedding response from OpenAI");
        }

        return response.getData().stream()
                .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex()))
                .map(EmbeddingResponse.EmbeddingData::getEmbedding)
                .toList();
    }

    /**
     * Chat completion with GPT-5.1.
     */
    public String chatCompletion(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_tokens", maxTokens,
                "temperature", temperature
        );

        ChatResponse response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .block();

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new RuntimeException("Empty chat response from OpenAI");
        }

        return response.getChoices().getFirst().getMessage().getContent();
    }

    // --- Response DTOs ---

    @Data
    public static class EmbeddingResponse {
        private List<EmbeddingData> data;

        @Data
        public static class EmbeddingData {
            private int index;
            private float[] embedding;
        }
    }

    @Data
    public static class ChatResponse {
        private List<Choice> choices;

        @Data
        public static class Choice {
            private Message message;
        }

        @Data
        public static class Message {
            private String role;
            private String content;
        }
    }
}
