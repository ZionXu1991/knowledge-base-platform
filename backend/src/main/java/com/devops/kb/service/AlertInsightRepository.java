package com.devops.kb.service;

import com.devops.kb.model.AlertInsightRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AlertInsightRepository {

    private final DynamoDbTable<AlertInsightRecord> alertInsightTable;

    /**
     * Save an alert insight record.
     */
    public void save(AlertInsightRecord record) {
        alertInsightTable.putItem(record);
        log.debug("Saved alert insight: {} / {}", record.getSource(), record.getAlertId());
    }

    /**
     * Get a single alert by source + alertId.
     */
    public AlertInsightRecord findById(String source, String alertId) {
        Key key = Key.builder()
                .partitionValue(source)
                .sortValue(alertId)
                .build();
        return alertInsightTable.getItem(key);
    }

    /**
     * List alerts by source (e.g., "appd"), newest first.
     */
    public List<AlertInsightRecord> findBySource(String source, int limit) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(source).build()))
                .scanIndexForward(false)  // descending (newest alertId first)
                .limit(limit)
                .build();

        return alertInsightTable.query(request)
                .items()
                .stream()
                .toList();
    }

    /**
     * List alerts by severity using GSI, newest first.
     */
    public List<AlertInsightRecord> findBySeverity(String severity, int limit) {
        DynamoDbIndex<AlertInsightRecord> index = alertInsightTable.index("severity-index");

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(severity).build()))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        return index.query(request)
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    /**
     * List all recent alerts (scan — use sparingly).
     */
    public List<AlertInsightRecord> findRecent(int limit) {
        return alertInsightTable.scan(r -> r.limit(limit))
                .items()
                .stream()
                .limit(limit)
                .toList();
    }

    /**
     * Update alert status (e.g., ACKNOWLEDGED, RESOLVED).
     */
    public void updateStatus(String source, String alertId, String status) {
        AlertInsightRecord record = findById(source, alertId);
        if (record != null) {
            record.setStatus(status);
            alertInsightTable.updateItem(record);
        }
    }
}
