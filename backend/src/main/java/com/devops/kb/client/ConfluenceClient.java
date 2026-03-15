package com.devops.kb.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class ConfluenceClient {

    private final WebClient webClient;

    public ConfluenceClient(
            @Value("${confluence.base-url}") String baseUrl,
            @Value("${confluence.username:}") String username,
            @Value("${confluence.api-token:}") String apiToken) {

        String credentials = Base64.getEncoder()
                .encodeToString((username + ":" + apiToken).getBytes(StandardCharsets.UTF_8));

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Fetch all pages in a space with body content, paginated.
     */
    public List<ConfluencePage> fetchSpacePages(String spaceKey) {
        List<ConfluencePage> allPages = new ArrayList<>();
        int start = 0;
        int limit = 50;
        boolean hasMore = true;

        while (hasMore) {
            String cql = String.format("space=%s AND type=page", spaceKey);
            final int currentStart = start;
            ConfluenceSearchResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/rest/api/content/search")
                            .queryParam("cql", cql)
                            .queryParam("start", currentStart)
                            .queryParam("limit", limit)
                            .queryParam("expand", "body.storage,version,space,ancestors")
                            .build())
                    .retrieve()
                    .bodyToMono(ConfluenceSearchResponse.class)
                    .block();

            if (response == null || response.getResults() == null) break;

            for (var result : response.getResults()) {
                ConfluencePage page = new ConfluencePage();
                page.setId(result.getId());
                page.setTitle(result.getTitle());
                page.setSpaceKey(spaceKey);
                page.setUrl(result.getLinks() != null ? result.getLinks().getWebui() : "");
                page.setLastModified(result.getVersion() != null ? result.getVersion().getWhen() : "");

                // Extract plain text from HTML storage format
                String html = (result.getBody() != null && result.getBody().getStorage() != null)
                        ? result.getBody().getStorage().getValue() : "";
                page.setPlainText(Jsoup.parse(html).text());

                allPages.add(page);
            }

            hasMore = response.getResults().size() == limit;
            start += limit;
        }

        log.info("Fetched {} pages from space {}", allPages.size(), spaceKey);
        return allPages;
    }

    // --- DTOs ---

    @Data
    public static class ConfluencePage {
        private String id;
        private String title;
        private String spaceKey;
        private String url;
        private String plainText;
        private String lastModified;
    }

    @Data
    public static class ConfluenceSearchResponse {
        private List<ConfluenceResult> results;
        private int start;
        private int limit;
        private int size;
    }

    @Data
    public static class ConfluenceResult {
        private String id;
        private String title;
        private ConfluenceBody body;
        private ConfluenceVersion version;
        private ConfluenceLinks _links;

        public ConfluenceLinks getLinks() { return _links; }
    }

    @Data
    public static class ConfluenceBody {
        private ConfluenceStorage storage;
    }

    @Data
    public static class ConfluenceStorage {
        private String value;
    }

    @Data
    public static class ConfluenceVersion {
        private String when;
    }

    @Data
    public static class ConfluenceLinks {
        private String webui;
    }
}
