package com.devops.kb.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class IngestStatus {
    private String status;  // IDLE, RUNNING, COMPLETED, FAILED
    private int totalPages;
    private int processedPages;
    private int totalChunks;
    private Instant lastSyncTime;
    private String error;
}
