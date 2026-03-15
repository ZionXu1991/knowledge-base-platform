package com.devops.kb.service;

import com.devops.kb.client.OpenAiClient;
import com.devops.kb.model.QueryRequest;
import com.devops.kb.model.QueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Core RAG engine: embed query -> vector search -> LLM generation with context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final OpenAiClient openAiClient;
    private final VectorStoreService vectorStoreService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${qdrant.collections.confluence}")
    private String confluenceCollection;

    @Value("${qdrant.collections.incidents}")
    private String incidentsCollection;

    @Value("${qdrant.collections.runbooks}")
    private String runbooksCollection;

    @Value("${rag.top-k}")
    private int defaultTopK;

    @Value("${rag.score-threshold}")
    private double defaultScoreThreshold;

    @Value("${rag.cache-ttl-minutes}")
    private int cacheTtlMinutes;

    private static final String SYSTEM_PROMPT = """
            You are an expert DevOps/SRE knowledge assistant. Your role is to help engineers
            find answers to technical questions using the knowledge base.

            Instructions:
            - Answer based ONLY on the provided context. If the context doesn't contain
              enough information, say so clearly.
            - Cite your sources using [Source: title] format.
            - Be concise and actionable. Engineers need quick, accurate answers.
            - If the question involves a procedure, provide step-by-step instructions.
            - If multiple approaches exist, briefly list pros/cons.
            - Use code blocks for commands, configs, or code snippets.
            """;

    public QueryResponse query(QueryRequest request) {
        long startTime = System.currentTimeMillis();

        // Check cache
        String cacheKey = "kb:query:" + hashQuery(request.getQuestion());
        QueryResponse cached = (QueryResponse) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for query: {}", request.getQuestion());
            cached.setLatencyMs(System.currentTimeMillis() - startTime);
            return cached;
        }

        // Determine which collections to search
        List<String> collections = request.getCollections();
        if (collections == null || collections.isEmpty()) {
            collections = List.of(confluenceCollection, incidentsCollection, runbooksCollection);
        }

        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        double threshold = request.getScoreThreshold() != null
                ? request.getScoreThreshold() : defaultScoreThreshold;

        // Step 1: Embed the query
        float[] queryVector = openAiClient.embed(request.getQuestion());

        // Step 2: Search Qdrant
        List<VectorStoreService.SearchResult> searchResults =
                vectorStoreService.searchMultiple(collections, queryVector, topK, threshold);

        // Step 3: Build context from search results
        StringBuilder contextBuilder = new StringBuilder();
        List<QueryResponse.Source> sources = searchResults.stream().map(sr -> {
            String text = sr.getPayload().getOrDefault("text", "");
            String title = sr.getPayload().getOrDefault("title", "Unknown");
            contextBuilder.append("--- Source: ").append(title).append(" ---\n");
            contextBuilder.append(text).append("\n\n");

            return QueryResponse.Source.builder()
                    .title(title)
                    .url(sr.getPayload().getOrDefault("url", ""))
                    .collection(sr.getPayload().getOrDefault("_collection", ""))
                    .score(sr.getScore())
                    .snippet(text.length() > 200 ? text.substring(0, 200) + "..." : text)
                    .build();
        }).toList();

        // Step 4: Generate answer with LLM
        String userMessage = String.format("""
                Context from knowledge base:
                %s

                Question: %s

                Please provide a clear, actionable answer based on the context above.
                """, contextBuilder, request.getQuestion());

        String answer;
        double confidence;

        if (searchResults.isEmpty()) {
            answer = "I couldn't find relevant information in the knowledge base for your question. "
                    + "Try rephrasing or check if the relevant documentation has been ingested.";
            confidence = 0.0;
        } else {
            answer = openAiClient.chatCompletion(SYSTEM_PROMPT, userMessage);
            confidence = searchResults.stream().mapToDouble(VectorStoreService.SearchResult::getScore).average().orElse(0);
        }

        QueryResponse response = QueryResponse.builder()
                .answer(answer)
                .confidence(confidence)
                .sources(sources)
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();

        // Cache the result
        redisTemplate.opsForValue().set(cacheKey, response, cacheTtlMinutes, TimeUnit.MINUTES);

        return response;
    }

    /**
     * Semantic search only (no LLM generation) - for search page.
     */
    public List<QueryResponse.Source> semanticSearch(String query, List<String> collections,
                                                      int topK, double threshold) {
        if (collections == null || collections.isEmpty()) {
            collections = List.of(confluenceCollection, incidentsCollection, runbooksCollection);
        }

        float[] queryVector = openAiClient.embed(query);
        List<VectorStoreService.SearchResult> results =
                vectorStoreService.searchMultiple(collections, queryVector, topK, threshold);

        return results.stream().map(sr -> QueryResponse.Source.builder()
                .title(sr.getPayload().getOrDefault("title", "Unknown"))
                .url(sr.getPayload().getOrDefault("url", ""))
                .collection(sr.getPayload().getOrDefault("_collection", ""))
                .score(sr.getScore())
                .snippet(sr.getPayload().getOrDefault("text", "").substring(0,
                        Math.min(300, sr.getPayload().getOrDefault("text", "").length())))
                .build()
        ).toList();
    }

    private String hashQuery(String query) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(query.toLowerCase().trim().getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(query.hashCode());
        }
    }
}
