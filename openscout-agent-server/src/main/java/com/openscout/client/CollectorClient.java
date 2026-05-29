package com.openscout.client;

import com.openscout.config.OpenScoutProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CollectorClient {

    private final RestClient restClient;
    private final OpenScoutProperties properties;

    public CollectorClient(RestClient.Builder restClientBuilder, OpenScoutProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public List<RepoSummary> fetchMockRepos(String keyword) {
        String encodedKeyword = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        URI uri = URI.create(properties.getCollectorBaseUrl() + "/api/repos/mock?keyword=" + encodedKeyword);
        RepoListResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(RepoListResponse.class);
        return response == null ? List.of() : response.itemsOrEmpty();
    }
}
