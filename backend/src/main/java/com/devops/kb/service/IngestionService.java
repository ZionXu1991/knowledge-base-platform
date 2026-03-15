package com.devops.kb.service;

import com.devops.kb.client.ConfluenceClient;
import com.devops.kb.client.ConfluenceClient.ConfluencePage;
import com.devops.kb.client.OpenAiClient;
import com.devops.kb.model.IngestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles the full ingestion pipeline:
 * Confluence -> Chunk -> Embed -> Qdrant upsert
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final ConfluenceClient confluenceClient;
    private final ChunkingService chunkingService;
    private final OpenAiClient openAiClient;
    private final VectorStoreService vectorStoreService;

    @Value("${confluence.spaces}")
    private String configuredSpaces;

    @Value("${qdrant.collections.confluence}")
    private String confluenceCollection;

    private final AtomicReference<IngestStatus> currentStatus = new AtomicReference<>(
            IngestStatus.builder().status("IDLE").build()
    );

    private static final int EMBED_BATCH_SIZE = 50;

    public IngestStatus getStatus() {
        return currentStatus.get();
    }

    /**
     * Scheduled sync using configured cron expression.
     */
    @Scheduled(cron = "${confluence.sync-cron}")
    public void scheduledSync() {
        log.info("Starting scheduled Confluence sync");
        syncConfluence(List.of(configuredSpaces.split(",")), false);
    }

    /**
     * Trigger manual sync for given spaces.
     */
    public IngestStatus syncConfluence(List<String> spaceKeys, boolean fullSync) {
        if ("RUNNING".equals(currentStatus.get().getStatus())) {
            return currentStatus.get();
        }

        List<String> spaces = (spaceKeys != null && !spaceKeys.isEmpty())
                ? spaceKeys
                : List.of(configuredSpaces.split(","));

        Thread.startVirtualThread(() -> runIngestion(spaces));

        IngestStatus status = IngestStatus.builder()
                .status("RUNNING")
                .totalPages(0)
                .processedPages(0)
                .build();
        currentStatus.set(status);
        return status;
    }

    private void runIngestion(List<String> spaces) {
        try {
            int totalPages = 0;
            int processedPages = 0;
            int totalChunks = 0;

            for (String space : spaces) {
                String spaceKey = space.trim();
                log.info("Syncing space: {}", spaceKey);

                List<ConfluencePage> pages = confluenceClient.fetchSpacePages(spaceKey);
                totalPages += pages.size();
                currentStatus.set(IngestStatus.builder()
                        .status("RUNNING")
                        .totalPages(totalPages)
                        .processedPages(processedPages)
                        .build());

                for (ConfluencePage page : pages) {
                    try {
                        Map<String, String> metadata = Map.of(
                                "page_id", page.getId(),
                                "title", page.getTitle(),
                                "space_key", page.getSpaceKey(),
                                "url", page.getUrl(),
                                "last_modified", page.getLastModified(),
                                "source", "confluence"
                        );

                        List<ChunkingService.Chunk> chunks =
                                chunkingService.chunkDocument(page.getPlainText(), metadata);

                        // Batch embed and upsert
                        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
                            List<ChunkingService.Chunk> batch = chunks.subList(
                                    i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()));

                            List<String> texts = batch.stream().map(ChunkingService.Chunk::getText).toList();
                            List<float[]> embeddings = openAiClient.embedBatch(texts);

                            List<String> ids = batch.stream()
                                    .map(c -> page.getId() + "_chunk_" + c.getIndex())
                                    .toList();

                            List<Map<String, String>> payloads = batch.stream()
                                    .map(c -> {
                                        Map<String, String> p = new HashMap<>(c.getMetadata());
                                        p.put("chunk_index", String.valueOf(c.getIndex()));
                                        p.put("text", c.getText());
                                        return p;
                                    }).toList();

                            vectorStoreService.upsertBatch(confluenceCollection, ids, embeddings, payloads);
                            totalChunks += batch.size();
                        }

                        processedPages++;
                        currentStatus.set(IngestStatus.builder()
                                .status("RUNNING")
                                .totalPages(totalPages)
                                .processedPages(processedPages)
                                .totalChunks(totalChunks)
                                .build());

                    } catch (Exception e) {
                        log.error("Failed to process page {}: {}", page.getTitle(), e.getMessage());
                    }
                }
            }

            currentStatus.set(IngestStatus.builder()
                    .status("COMPLETED")
                    .totalPages(totalPages)
                    .processedPages(processedPages)
                    .totalChunks(totalChunks)
                    .lastSyncTime(Instant.now())
                    .build());

            log.info("Ingestion complete: {} pages, {} chunks", processedPages, totalChunks);

        } catch (Exception e) {
            log.error("Ingestion failed", e);
            currentStatus.set(IngestStatus.builder()
                    .status("FAILED")
                    .error(e.getMessage())
                    .lastSyncTime(Instant.now())
                    .build());
        }
    }
}
