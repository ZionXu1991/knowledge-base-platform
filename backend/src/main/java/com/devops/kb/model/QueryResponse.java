package com.devops.kb.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QueryResponse {
    private String answer;
    private double confidence;
    private List<Source> sources;
    private long latencyMs;

    @Data
    @Builder
    public static class Source {
        private String title;
        private String url;
        private String collection;
        private double score;
        private String snippet;
    }
}
