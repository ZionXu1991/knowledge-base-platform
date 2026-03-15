package com.devops.kb.controller;

import com.devops.kb.model.*;
import com.devops.kb.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/kb")
@RequiredArgsConstructor
@Tag(name = "Knowledge Base", description = "RAG-powered knowledge base Q&A and alert analysis")
public class KnowledgeBaseController {

    private final RagQueryService ragQueryService;
    private final AlertAnalysisService alertAnalysisService;
    private final IngestionService ingestionService;
    private final VectorStoreService vectorStoreService;

    @Value("${qdrant.collections.confluence}")
    private String confluenceCollection;

    @Value("${qdrant.collections.incidents}")
    private String incidentsCollection;

    @Value("${qdrant.collections.runbooks}")
    private String runbooksCollection;

    // ─────────────────── Q&A ───────────────────

    @PostMapping("/query")
    @Operation(summary = "Ask a question",
               description = "RAG pipeline: embed query -> vector search -> LLM answer with citations")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        return ResponseEntity.ok(ragQueryService.query(request));
    }

    @GetMapping("/search")
    @Operation(summary = "Semantic search",
               description = "Vector similarity search without LLM generation")
    public ResponseEntity<List<QueryResponse.Source>> search(
            @RequestParam String q,
            @RequestParam(required = false) List<String> collections,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "0.7") double threshold) {
        return ResponseEntity.ok(ragQueryService.semanticSearch(q, collections, topK, threshold));
    }

    // ─────────────────── Alert Analysis ───────────────────

    @PostMapping("/alert-analyze")
    @Operation(summary = "Analyze monitoring alert",
               description = "Receives alert from n8n/AppD/Splunk, analyzes via RAG, persists to DynamoDB")
    public ResponseEntity<AlertAnalyzeResponse> analyzeAlert(
            @Valid @RequestBody AlertAnalyzeRequest request) {
        return ResponseEntity.ok(alertAnalysisService.analyze(request));
    }

    @GetMapping("/alerts")
    @Operation(summary = "List alert insights from DynamoDB",
               description = "Filter by source (appd/splunk) or severity (CRITICAL/HIGH/MEDIUM/LOW)")
    public ResponseEntity<List<AlertAnalyzeResponse>> listAlerts(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(alertAnalysisService.getAlertHistory(source, severity, limit));
    }

    @GetMapping("/alerts/{source}/{alertId}")
    @Operation(summary = "Get single alert insight detail")
    public ResponseEntity<AlertAnalyzeResponse> getAlert(
            @PathVariable String source,
            @PathVariable String alertId) {
        AlertAnalyzeResponse result = alertAnalysisService.getAlertDetail(source, alertId);
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }

    @PatchMapping("/alerts/{source}/{alertId}/status")
    @Operation(summary = "Update alert status (ACKNOWLEDGED, RESOLVED)")
    public ResponseEntity<Void> updateAlertStatus(
            @PathVariable String source,
            @PathVariable String alertId,
            @RequestParam String status) {
        alertAnalysisService.updateAlertStatus(source, alertId, status);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────── Ingestion ───────────────────

    @PostMapping("/ingest/confluence")
    @Operation(summary = "Trigger Confluence sync",
               description = "Start ingestion of Confluence pages into vector store")
    public ResponseEntity<IngestStatus> ingestConfluence(@RequestBody(required = false) IngestRequest request) {
        List<String> spaces = request != null ? request.getSpaceKeys() : null;
        boolean fullSync = request != null && request.isFullSync();
        return ResponseEntity.accepted().body(ingestionService.syncConfluence(spaces, fullSync));
    }

    @GetMapping("/sync-status")
    @Operation(summary = "Check ingestion status")
    public ResponseEntity<IngestStatus> getSyncStatus() {
        return ResponseEntity.ok(ingestionService.getStatus());
    }

    // ─────────────────── Admin ───────────────────

    @GetMapping("/sources")
    @Operation(summary = "List ingested sources with counts")
    public ResponseEntity<Map<String, Long>> getSources() {
        return ResponseEntity.ok(Map.of(
                confluenceCollection, vectorStoreService.getCollectionSize(confluenceCollection),
                incidentsCollection, vectorStoreService.getCollectionSize(incidentsCollection),
                runbooksCollection, vectorStoreService.getCollectionSize(runbooksCollection)
        ));
    }

    @DeleteMapping("/collection/{name}")
    @Operation(summary = "Clear a collection")
    public ResponseEntity<Void> clearCollection(@PathVariable String name) {
        vectorStoreService.clearCollection(name);
        return ResponseEntity.noContent().build();
    }
}
