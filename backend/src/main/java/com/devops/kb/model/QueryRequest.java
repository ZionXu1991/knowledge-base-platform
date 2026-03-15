package com.devops.kb.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class QueryRequest {
    @NotBlank(message = "Question is required")
    private String question;
    private List<String> collections;  // null = search all
    private Integer topK;              // override default
    private Double scoreThreshold;     // override default
}
