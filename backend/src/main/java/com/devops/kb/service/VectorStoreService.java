package com.devops.kb.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Abstraction over Qdrant for upsert and search operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final QdrantClient qdrantClient;

    @Data
    @Builder
    public static class SearchResult {
        private String id;
        private double score;
        private Map<String, String> payload;
    }

    /**
     * Upsert a batch of vectors with payloads into a collection.
     */
    public void upsertBatch(String collection, List<String> ids, List<float[]> vectors,
                            List<Map<String, String>> payloads) {
        List<PointStruct> points = new ArrayList<>();

        for (int i = 0; i < ids.size(); i++) {
            UUID pointId = UUID.nameUUIDFromBytes(ids.get(i).getBytes());
            Map<String, Value> payloadMap = new HashMap<>();
            payloads.get(i).forEach((k, v) -> payloadMap.put(k, value(v)));

            points.add(PointStruct.newBuilder()
                    .setId(id(pointId))
                    .setVectors(vectors(vectors.get(i)))
                    .putAllPayload(payloadMap)
                    .build());
        }

        try {
            qdrantClient.upsertAsync(collection, points, null).get();
            log.debug("Upserted {} points to collection {}", points.size(), collection);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to upsert to Qdrant: " + e.getMessage(), e);
        }
    }

    /**
     * Semantic search: find top-k most similar vectors.
     */
    public List<SearchResult> search(String collection, float[] queryVector, int topK, double scoreThreshold) {
        try {
            List<ScoredPoint> results = qdrantClient.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(collection)
                            .addAllVector(toFloatList(queryVector))
                            .setLimit(topK)
                            .setScoreThreshold((float) scoreThreshold)
                            .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                            .build()
            ).get();

            return results.stream().map(sp -> {
                Map<String, String> payload = new HashMap<>();
                sp.getPayloadMap().forEach((k, v) -> payload.put(k, v.getStringValue()));
                return SearchResult.builder()
                        .id(sp.getId().getUuid())
                        .score(sp.getScore())
                        .payload(payload)
                        .build();
            }).toList();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Qdrant search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Search across multiple collections and merge results.
     */
    public List<SearchResult> searchMultiple(List<String> collections, float[] queryVector,
                                              int topK, double scoreThreshold) {
        List<SearchResult> merged = new ArrayList<>();
        for (String collection : collections) {
            List<SearchResult> results = search(collection, queryVector, topK, scoreThreshold);
            results.forEach(r -> r.getPayload().put("_collection", collection));
            merged.addAll(results);
        }
        merged.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return merged.stream().limit(topK).toList();
    }

    /**
     * Delete all points in a collection.
     */
    public void clearCollection(String collection) {
        try {
            qdrantClient.deleteCollectionAsync(collection).get();
            log.info("Cleared collection: {}", collection);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to clear collection: " + e.getMessage(), e);
        }
    }

    /**
     * Get point count for a collection.
     */
    public long getCollectionSize(String collection) {
        try {
            var info = qdrantClient.getCollectionInfoAsync(collection).get();
            return info.getPointsCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) list.add(f);
        return list;
    }
}
