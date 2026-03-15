package com.devops.kb.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class AlertAnalyzeRequest {
    @NotBlank
    private String errorMessage;
    private String applicationName;
    private String severity;       // CRITICAL, HIGH, MEDIUM, LOW
    private String source;         // appd, splunk
    private String stackTrace;
    private Map<String, String> metadata;
}
