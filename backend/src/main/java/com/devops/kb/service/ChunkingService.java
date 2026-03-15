package com.devops.kb.service;

import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive text splitter that breaks documents into overlapping chunks
 * optimized for embedding and retrieval.
 */
@Service
public class ChunkingService {

    @Value("${rag.chunk-size}")
    private int chunkSize;

    @Value("${rag.chunk-overlap}")
    private int chunkOverlap;

    @Data
    @Builder
    public static class Chunk {
        private String text;
        private int index;
        private Map<String, String> metadata;
    }

    /**
     * Split text into chunks with overlap.
     * Uses paragraph -> sentence -> word boundaries as split points.
     */
    public List<Chunk> chunkDocument(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String para : paragraphs) {
            if (current.length() + para.length() > chunkSize && !current.isEmpty()) {
                chunks.add(Chunk.builder()
                        .text(current.toString().trim())
                        .index(chunkIndex++)
                        .metadata(metadata)
                        .build());

                // Keep overlap from the end of current chunk
                String overlapText = getOverlapText(current.toString(), chunkOverlap);
                current = new StringBuilder(overlapText);
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(para);
        }

        // Remaining text
        if (!current.isEmpty()) {
            String remaining = current.toString().trim();
            // If remaining is too long, split by sentences
            if (remaining.length() > chunkSize) {
                chunks.addAll(splitBySentence(remaining, chunkIndex, metadata));
            } else if (!remaining.isBlank()) {
                chunks.add(Chunk.builder()
                        .text(remaining)
                        .index(chunkIndex)
                        .metadata(metadata)
                        .build());
            }
        }

        return chunks;
    }

    private List<Chunk> splitBySentence(String text, int startIndex, Map<String, String> metadata) {
        List<Chunk> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        int idx = startIndex;

        for (String sentence : sentences) {
            if (current.length() + sentence.length() > chunkSize && !current.isEmpty()) {
                chunks.add(Chunk.builder()
                        .text(current.toString().trim())
                        .index(idx++)
                        .metadata(metadata)
                        .build());
                String overlap = getOverlapText(current.toString(), chunkOverlap);
                current = new StringBuilder(overlap);
            }
            current.append(sentence).append(" ");
        }

        if (!current.isEmpty() && !current.toString().isBlank()) {
            chunks.add(Chunk.builder()
                    .text(current.toString().trim())
                    .index(idx)
                    .metadata(metadata)
                    .build());
        }

        return chunks;
    }

    private String getOverlapText(String text, int overlapChars) {
        if (text.length() <= overlapChars) return text;
        String tail = text.substring(text.length() - overlapChars);
        // Try to start at a word boundary
        int spaceIdx = tail.indexOf(' ');
        return spaceIdx > 0 ? tail.substring(spaceIdx + 1) : tail;
    }
}
