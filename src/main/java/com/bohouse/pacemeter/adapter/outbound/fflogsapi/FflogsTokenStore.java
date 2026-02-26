package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

/**
 * FFLogs OAuth2 Client Credentials 토큰 관리.
 * 만료 60초 전 자동 갱신.
 */
@Component
public class FflogsTokenStore {

    private static final Logger log = LoggerFactory.getLogger(FflogsTokenStore.class);

    @Value("${pacemeter.fflogs.client-id:}")
    private String clientId;

    @Value("${pacemeter.fflogs.client-secret:}")
    private String clientSecret;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private volatile String accessToken = null;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public FflogsTokenStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create("https://www.fflogs.com");
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }

    public synchronized Optional<String> getToken() {
        if (!isConfigured()) return Optional.empty();
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return Optional.of(accessToken);
        }
        return refreshToken();
    }

    private Optional<String> refreshToken() {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            String response = restClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            String token = node.path("access_token").asText("");
            long expiresIn = node.path("expires_in").asLong(3600);

            if (token.isBlank()) {
                log.error("[FFLogs] token refresh: empty access_token in response");
                return Optional.empty();
            }

            accessToken = token;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
            log.info("[FFLogs] token refreshed, expires in {}s", expiresIn);
            return Optional.of(accessToken);

        } catch (Exception e) {
            log.error("[FFLogs] token refresh failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}