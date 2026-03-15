package com.devops.kb.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.List;

/**
 * DynamoDB entity for persisted alert analysis results.
 *
 * Table: kb_alert_insights
 * PK: source (appd / splunk / datadog)
 * SK: alertId (UUID, sortable by time)
 * GSI: severity-index (PK=severity, SK=createdAt) for filtering by severity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class AlertInsightRecord {

    private String alertId;           // UUID — sort key
    private String source;            // appd / splunk — partition key
    private String severity;
    private String applicationName;
    private String errorMessage;
    private String stackTrace;
    private String rootCauseAnalysis;
    private String recommendedActionsJson;   // JSON array string
    private String similarIncidentsJson;     // JSON array string
    private String relatedKnowledgeJson;     // JSON array string
    private double confidence;
    private String createdAt;         // ISO-8601 timestamp
    private String status;            // NEW, ACKNOWLEDGED, RESOLVED

    @DynamoDbPartitionKey
    public String getSource() { return source; }

    @DynamoDbSortKey
    public String getAlertId() { return alertId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "severity-index")
    public String getSeverity() { return severity; }

    @DynamoDbSecondarySortKey(indexNames = "severity-index")
    public String getCreatedAt() { return createdAt; }
}
