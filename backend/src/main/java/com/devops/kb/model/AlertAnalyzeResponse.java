package com.devops.kb.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AlertAnalyzeResponse {
    private String alertId;
    private String source;
    private String applicationName;
    private String errorMessage;
    private String rootCauseAnalysis;
    private List<String> recommendedActions;
    private List<QueryResponse.Source> relatedKnowledge;
    private String severity;
    private double confidence;
    private List<SimilarIncident> similarIncidents;
    private String createdAt;
    private String status;

    @Data
    @Builder
    public static class SimilarIncident {
        private String incidentId;
        private String errorType;
        private String resolution;
        private double similarity;
    }
}
