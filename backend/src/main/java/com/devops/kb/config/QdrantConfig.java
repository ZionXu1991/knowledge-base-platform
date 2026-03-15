package com.devops.kb.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private int port;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Value("${openai.embedding-dimensions}")
    private int embeddingDimensions;

    @Value("${qdrant.collections.confluence}")
    private String confluenceCollection;

    @Value("${qdrant.collections.incidents}")
    private String incidentsCollection;

    @Value("${qdrant.collections.runbooks}")
    private String runbooksCollection;

    @Bean
    public QdrantClient qdrantClient() {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, false);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initCollections() {
        try {
            QdrantClient client = qdrantClient();
            var existingCollections = client.listCollectionsAsync().get();

            for (String collectionName : new String[]{confluenceCollection, incidentsCollection, runbooksCollection}) {
                if (!existingCollections.contains(collectionName)) {
                    client.createCollectionAsync(collectionName,
                            VectorParams.newBuilder()
                                    .setDistance(Distance.Cosine)
                                    .setSize(embeddingDimensions)
                                    .build()
                    ).get();
                    log.info("Created Qdrant collection: {}", collectionName);
                } else {
                    log.info("Qdrant collection already exists: {}", collectionName);
                }
            }
        } catch (Exception e) {
            log.warn("Could not initialize Qdrant collections (will retry on first use): {}", e.getMessage());
        }
    }
}
