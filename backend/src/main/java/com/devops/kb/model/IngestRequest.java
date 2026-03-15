package com.devops.kb.model;

import lombok.Data;

import java.util.List;

@Data
public class IngestRequest {
    private List<String> spaceKeys;  // null = use configured spaces
    private boolean fullSync;        // true = re-ingest everything
}
