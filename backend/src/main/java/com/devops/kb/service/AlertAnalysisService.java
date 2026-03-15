package com.devops.kb.service;

import com.devops.kb.client.OpenAiClient;
import com.devops.kb.model.AlertAnalyzeRequest;
import com.devops.kb.model.AlertAnalyzeResponse;
import com.devops.kb.model.AlertInsightRecord;
import com.devops.kb.model.QueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Analyzes monitoring alerts (AppDynamics, Splunk) against the knowledge base,
 * generates RCA + recommended actions via GPT-5.1, and persists results to DynamoDB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertAnalysisService {

    private final OpenAiClient openAiClient;
    private final VectorStoreService vectorStoreService;
    private final AlertInsightRepository alertInsightRepository;
    private final ObjectMapper objectMapper;

    @Value("${qdrant.collections.confluence}")
    private String confluenceCollection;

    @Value("${qdrant.collections.incidents}")
    private String incidentsCollection;

    @Value("${qdrant.collections.runbooks}")
    private String runbooksCollection;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.score-threshold}")
    private double scoreThreshold;

    private static final String ALERT_ANALYSIS_PROMPT = """
            You are an expert SRE/DevOps incident analyst. Given a monitoring alert and
            relevant knowledge base context, provide:

            1. **Root Cause Analysis**: What is the most likely cause of this error?
            2. **Recommended Actions**: Step-by-step actions to resolve this issue, ordered by priority.
            3. **Severity Assessment**: Based on the error pattern, assess the actual severity.

            Be specific and actionable. Reference the knowledge base sources when applicable.
            If similar incidents have been resolved before, highlight the resolution that worked.
            Format your response as structured sections.
            """;

    /**
     * Analyze alert, generate insights, persist to DynamoDB, return response.
     */
    public AlertAnalyzeResponse analyze(AlertAnalyzeRequest request) {
        log.info("Analyzing alert from {}: {}", request.getSource(), request.getErrorMessage());

        // Build search query from error context
        String searchQuery = buildSearchQuery(request);
        float[] queryVector = openAiClient.embed(searchQuery);

        // Search all 3 collections
        List<VectorStoreService.SearchResult> incidentResults =
                vectorStoreService.search(incidentsCollection, queryVector, topK, scoreThreshold);
        List<VectorStoreService.SearchResult> runbookResults =
                vectorStoreService.search(runbooksCollection, queryVector, 3, scoreThreshold);
        List<VectorStoreService.SearchResult> confluenceResults =
                vectorStoreService.search(confluenceCollection, queryVector, 3, scoreThreshold);

        // Build context for LLM
        StringBuilder context = new StringBuilder();

        context.append("=== SIMILAR PAST INCIDENTS ===\n");
        for (var r : incidentResults) {
            context.append("- Error: ").append(r.getPayload().getOrDefault("error_type", ""))
                    .append(" | Resolution: ").append(r.getPayload().getOrDefault("resolution", ""))
                    .append(" | Similarity: ").append(String.format("%.2f", r.getScore())).append("\n");
        }

        context.append("\n=== RELEVANT RUNBOOKS ===\n");
        for (var r : runbookResults) {
            context.append("- ").append(r.getPayload().getOrDefault("title", ""))
                    .append(": ").append(r.getPayload().getOrDefault("text", "")).append("\n");
        }

        context.append("\n=== KNOWLEDGE BASE ARTICLES ===\n");
        for (var r : confluenceResults) {
            context.append("- ").append(r.getPayload().getOrDefault("title", ""))
                    .append(": ").append(r.getPayload().getOrDefault("text", "")).append("\n");
        }

        String userMessage = String.format("""
                Alert Details:
                - Source: %s
                - Application: %s
                - Severity: %s
                - Error Message: %s
                - Stack Trace: %s

                Knowledge Base Context:
                %s

                Provide root cause analysis and recommended actions.
                """,
                request.getSource(),
                request.getApplicationName(),
                request.getSeverity(),
                request.getErrorMessage(),
                request.getStackTrace() != null ? request.getStackTrace() : "N/A",
                context
        );

        String llmResponse = openAiClient.chatCompletion(ALERT_ANALYSIS_PROMPT, userMessage);
        List<String> recommendedActions = extractActions(llmResponse);

        List<AlertAnalyzeResponse.SimilarIncident> similarIncidents = incidentResults.stream()
                .map(r -> AlertAnalyzeResponse.SimilarIncident.builder()
                        .incidentId(r.getPayload().getOrDefault("incident_id", r.getId()))
                        .errorType(r.getPayload().getOrDefault("error_type", ""))
                        .resolution(r.getPayload().getOrDefault("resolution", ""))
                        .similarity(r.getScore())
                        .build())
                .toList();

        List<QueryResponse.Source> relatedKnowledge = new ArrayList<>();
        addSources(relatedKnowledge, runbookResults, "runbooks");
        addSources(relatedKnowledge, confluenceResults, "confluence");

        double confidence = incidentResults.isEmpty() ? 0.3 :
                incidentResults.stream().mapToDouble(VectorStoreService.SearchResult::getScore).average().orElse(0.5);

        String alertId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        // Build response
        AlertAnalyzeResponse response = AlertAnalyzeResponse.builder()
                .alertId(alertId)
                .source(request.getSource())
                .applicationName(request.getApplicationName())
                .errorMessage(request.getErrorMessage())
                .rootCauseAnalysis(llmResponse)
                .recommendedActions(recommendedActions)
                .relatedKnowledge(relatedKnowledge)
                .severity(request.getSeverity())
                .confidence(confidence)
                .similarIncidents(similarIncidents)
                .createdAt(now)
                .status("NEW")
                .build();

        // Persist to DynamoDB
        persistToDynamoDB(response, request);

        return response;
    }

    /**
     * Get alert history from DynamoDB.
     */
    public List<AlertAnalyzeResponse> getAlertHistory(String source, String severity, int limit) {
        List<AlertInsightRecord> records;

        if (severity != null && !severity.isBlank()) {
            records = alertInsightRepository.findBySeverity(severity, limit);
        } else if (source != null && !source.isBlank()) {
            records = alertInsightRepository.findBySource(source, limit);
        } else {
            records = alertInsightRepository.findRecent(limit);
        }

        return records.stream().map(this::recordToResponse).toList();
    }

    /**
     * Get single alert detail.
     */
    public AlertAnalyzeResponse getAlertDetail(String source, String alertId) {
        AlertInsightRecord record = alertInsightRepository.findById(source, alertId);
        return record != null ? recordToResponse(record) : null;
    }

    /**
     * Update alert status.
     */
    public void updateAlertStatus(String source, String alertId, String status) {
        alertInsightRepository.updateStatus(source, alertId, status);
    }

    // ─────────────────── Private helpers ───────────────────

    private void persistToDynamoDB(AlertAnalyzeResponse response, AlertAnalyzeRequest request) {
        try {
            AlertInsightRecord record = AlertInsightRecord.builder()
                    .alertId(response.getAlertId())
                    .source(response.getSource() != null ? response.getSource() : "unknown")
                    .severity(response.getSeverity())
                    .applicationName(response.getApplicationName())
                    .errorMessage(response.getErrorMessage())
                    .stackTrace(request.getStackTrace())
                    .rootCauseAnalysis(response.getRootCauseAnalysis())
                    .recommendedActionsJson(objectMapper.writeValueAsString(response.getRecommendedActions()))
                    .similarIncidentsJson(objectMapper.writeValueAsString(response.getSimilarIncidents()))
                    .relatedKnowledgeJson(objectMapper.writeValueAsString(response.getRelatedKnowledge()))
                    .confidence(response.getConfidence())
                    .createdAt(response.getCreatedAt())
                    .status("NEW")
                    .build();

            alertInsightRepository.save(record);
            log.info("Alert insight persisted to DynamoDB: {}", response.getAlertId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize alert insight for DynamoDB", e);
        }
    }

    private AlertAnalyzeResponse recordToResponse(AlertInsightRecord record) {
        List<String> actions = parseJsonList(record.getRecommendedActionsJson(), String.class);
        List<AlertAnalyzeResponse.SimilarIncident> incidents =
                parseJsonList(record.getSimilarIncidentsJson(), AlertAnalyzeResponse.SimilarIncident.class);
        List<QueryResponse.Source> knowledge =
                parseJsonList(record.getRelatedKnowledgeJson(), QueryResponse.Source.class);

        return AlertAnalyzeResponse.builder()
                .alertId(record.getAlertId())
                .source(record.getSource())
                .applicationName(record.getApplicationName())
                .errorMessage(record.getErrorMessage())
                .rootCauseAnalysis(record.getRootCauseAnalysis())
                .recommendedActions(actions)
                .relatedKnowledge(knowledge)
                .severity(record.getSeverity())
                .confidence(record.getConfidence())
                .similarIncidents(incidents)
                .createdAt(record.getCreatedAt())
                .status(record.getStatus())
                .build();
    }

    private <T> List<T> parseJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON list: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildSearchQuery(AlertAnalyzeRequest request) {
        StringBuilder query = new StringBuilder(request.getErrorMessage());
        if (request.getApplicationName() != null) {
            query.append(" ").append(request.getApplicationName());
        }
        if (request.getStackTrace() != null) {
            String[] lines = request.getStackTrace().split("\n");
            if (lines.length > 0) {
                query.append(" ").append(lines[0]);
            }
        }
        return query.toString();
    }

    private List<String> extractActions(String llmResponse) {
        return llmResponse.lines()
                .filter(line -> line.matches("^\\s*[\\d]+[.)\\s].*") || line.matches("^\\s*[-*]\\s.*"))
                .map(String::trim)
                .toList();
    }

    private void addSources(List<QueryResponse.Source> sources,
                            List<VectorStoreService.SearchResult> results, String collection) {
        results.forEach(r -> sources.add(QueryResponse.Source.builder()
                .title(r.getPayload().getOrDefault("title", "Unknown"))
                .url(r.getPayload().getOrDefault("url", ""))
                .collection(collection)
                .score(r.getScore())
                .snippet(r.getPayload().getOrDefault("text", "").substring(0,
                        Math.min(200, r.getPayload().getOrDefault("text", "").length())))
                .build()));
    }
}
