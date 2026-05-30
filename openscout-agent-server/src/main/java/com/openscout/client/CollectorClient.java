package com.openscout.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.config.OpenScoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

@Component
public class CollectorClient {

    private static final Logger log = LoggerFactory.getLogger(CollectorClient.class);

    private final RestClient restClient;
    private final OpenScoutProperties properties;
    private final ObjectMapper objectMapper;

    public CollectorClient(RestClient.Builder restClientBuilder, OpenScoutProperties properties,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    // ---- mock ----

    public List<RepoSummary> fetchMockRepos(String keyword) {
        String encodedKeyword = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        URI uri = URI.create(properties.getCollectorBaseUrl() + "/api/repos/mock?keyword=" + encodedKeyword);
        RepoListResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(RepoListResponse.class);
        return response == null ? List.of() : response.itemsOrEmpty();
    }

    // ---- real API ----

    public List<RepoSummary> searchRepos(String keyword, int limit, String mode) {
        String encodedKeyword = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        String effectiveMode = effectiveMode(mode);
        URI uri = URI.create(properties.getCollectorBaseUrl()
                + "/api/repos/search?keyword=" + encodedKeyword
                + "&limit=" + limit
                + "&mode=" + effectiveMode);
        return executeWithErrorHandling(() -> {
            RepoListResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(RepoListResponse.class);
            return response == null ? List.of() : response.itemsOrEmpty();
        });
    }

    public RepoSummary getProfile(String owner, String repo, String mode) {
        String effectiveMode = effectiveMode(mode);
        URI uri = URI.create(properties.getCollectorBaseUrl()
                + "/api/repos/" + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/profile?mode=" + effectiveMode);
        return executeWithErrorHandling(() -> restClient.get()
                .uri(uri)
                .retrieve()
                .body(RepoSummary.class));
    }

    public ReadmeResponse getReadme(String owner, String repo, String mode) {
        String effectiveMode = effectiveMode(mode);
        URI uri = URI.create(properties.getCollectorBaseUrl()
                + "/api/repos/" + URLEncoder.encode(owner, StandardCharsets.UTF_8)
                + "/" + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/readme?mode=" + effectiveMode);
        return executeWithErrorHandling(() -> restClient.get()
                .uri(uri)
                .retrieve()
                .body(ReadmeResponse.class));
    }

    public BatchProfileResponse batchProfile(List<String> repos, String mode) {
        String effectiveMode = effectiveMode(mode);
        URI uri = URI.create(properties.getCollectorBaseUrl()
                + "/api/repos/batch-profile?mode=" + effectiveMode);
        return executeWithErrorHandling(() -> restClient.post()
                .uri(uri)
                .body(new BatchProfileRequest(repos))
                .retrieve()
                .body(BatchProfileResponse.class));
    }

    // ---- error handling ----

    /**
     * 统一将 RestClient 异常翻译为 {@link CollectorException} 子类。
     */
    private <T> T executeWithErrorHandling(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException ex) {
            log.warn("Go Collector 不可达: {}", ex.getMessage());
            throw new CollectorUnavailableException(null,
                    "Go Collector 服务不可用，请确认 Collector 已启动: " + ex.getMessage(), ex);
        } catch (RestClientResponseException ex) {
            throw parseCollectorError(ex);
        }
    }

    /**
     * 解析 Go Collector 返回的结构化 ErrorResponse JSON。
     * 优先匹配 {@code code} 字段，失败时 fallback 到 {@code message} 字段。
     */
    private CollectorException parseCollectorError(RestClientResponseException ex) {
        ErrorResponseDto dto = parseErrorBody(ex);
        if (dto != null && dto.code != null) {
            return switch (dto.code) {
                case "RATE_LIMITED" -> new RateLimitException(null,
                        dto.error != null ? dto.error : ex.getMessage(),
                        dto.retryAfter);
                case "FORBIDDEN", "API_ERROR" -> new GitHubApiException(null,
                        dto.error != null ? dto.error : ex.getMessage(),
                        ex.getStatusCode().value(), dto.code);
                default -> new GitHubApiException(null,
                        dto.error != null ? dto.error : ex.getMessage(),
                        ex.getStatusCode().value(),
                        dto.code != null ? dto.code : "UNKNOWN");
            };
        }
        // fallback: parse raw response body for "message" field
        String fallbackMessage = dto != null && dto.error != null ? dto.error : ex.getMessage();
        return new GitHubApiException(null, fallbackMessage, ex.getStatusCode().value(), "UNKNOWN");
    }

    private ErrorResponseDto parseErrorBody(RestClientResponseException ex) {
        try {
            byte[] body = ex.getResponseBodyAsByteArray();
            if (body != null && body.length > 0) {
                return objectMapper.readValue(body, ErrorResponseDto.class);
            }
        } catch (Exception ignored) {
            log.debug("Failed to parse collector error body as ErrorResponseDto", ignored);
        }
        // fallback: try parsing legacy {"message":"..."} shape
        try {
            byte[] body = ex.getResponseBodyAsByteArray();
            if (body != null && body.length > 0) {
                LegacyErrorDto legacy = objectMapper.readValue(body, LegacyErrorDto.class);
                if (legacy.message != null) {
                    return new ErrorResponseDto(legacy.message, "INTERNAL", 0);
                }
            }
        } catch (Exception ignored) {
            log.debug("Failed to parse collector error body as legacy message", ignored);
        }
        return null;
    }

    private String effectiveMode(String modeOverride) {
        if (modeOverride != null && !modeOverride.isBlank()) {
            return modeOverride;
        }
        return properties.getCollectorMode();
    }

    // ---- DTOs for error parsing ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponseDto(String error, String code, int retryAfter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegacyErrorDto(String message) {
    }
}
