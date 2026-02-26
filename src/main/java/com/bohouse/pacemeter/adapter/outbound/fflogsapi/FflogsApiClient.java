package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FFLogs GraphQL API v2 클라이언트.
 * 1) 인카운터의 #1 rDPS 랭킹 조회
 * 2) 해당 파이트의 누적 데미지 타임라인 조회
 */
@Component
public class FflogsApiClient {

    private static final Logger log = LoggerFactory.getLogger(FflogsApiClient.class);
    private static final String API_URL = "https://www.fflogs.com/api/v2/client";

    private final FflogsTokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FflogsApiClient(FflogsTokenStore tokenStore, ObjectMapper objectMapper) {
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create(API_URL);
    }

    /**
     * 해당 인카운터의 rDPS 1위 랭킹 정보를 가져온다.
     *
     * @param encounterId FFLogs 인카운터 ID
     * @return 1위 파이트 정보 (reportCode, reportStartMs, fightStartMs, durationMs)
     */
    public Optional<TopRanking> fetchTopRanking(int encounterId) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchTopRanking skipped - no token");
            return Optional.empty();
        }

        // characterRankings는 JSON scalar를 반환하므로 필드 목록 없이 호출
        String query = """
                query($encounterId: Int!) {
                  worldData {
                    encounter(id: $encounterId) {
                      characterRankings(metric: rdps, page: 1)
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of("encounterId", encounterId)
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchTopRanking");

            JsonNode rankings = root.path("data").path("worldData")
                    .path("encounter").path("characterRankings").path("rankings");

            if (!rankings.isArray() || rankings.isEmpty()) {
                log.warn("[FFLogs] no rankings for encounterId={}", encounterId);
                return Optional.empty();
            }

            JsonNode r = rankings.get(0);
            String reportCode = r.path("report").path("code").asText("");
            long reportStartMs = r.path("report").path("startTime").asLong(0);
            long fightStartMs = r.path("startTime").asLong(0);
            long durationMs = r.path("duration").asLong(0);
            double amount = r.path("amount").asDouble(0);

            if (reportCode.isBlank() || durationMs <= 0) {
                log.warn("[FFLogs] invalid ranking data: code='{}' duration={}", reportCode, durationMs);
                return Optional.empty();
            }

            log.info("[FFLogs] top ranking: name={} rdps={} duration={}ms code={}",
                    r.path("name").asText(), (long) amount, durationMs, reportCode);

            return Optional.of(new TopRanking(reportCode, reportStartMs, fightStartMs, durationMs));

        } catch (Exception e) {
            log.error("[FFLogs] fetchTopRanking failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 특정 파이트의 누적 파티 데미지 타임라인을 가져온다.
     *
     * @param reportCode    FFLogs 리포트 코드
     * @param reportStartMs 리포트 시작 epoch ms (rankings의 report.startTime)
     * @param fightStartMs  파이트 시작 epoch ms (rankings의 startTime)
     * @param durationMs    파이트 지속 시간 ms
     * @return [[elapsedMs, cumulativeDamage], ...] 파이트 기준 타임라인
     */
    public List<long[]> fetchCumulativeDamageTimeline(
            String reportCode, long reportStartMs, long fightStartMs, long durationMs) {

        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) return List.of();

        // FFLogs graph의 startTime/endTime은 리포트 시작 기준 ms
        long queryStart = fightStartMs - reportStartMs;
        long queryEnd = queryStart + durationMs;

        String query = """
                query($code: String!, $startTime: Float!, $endTime: Float!) {
                  reportData {
                    report(code: $code) {
                      graph(
                        startTime: $startTime
                        endTime: $endTime
                        dataType: DamageDone
                      )
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of(
                            "code", reportCode,
                            "startTime", (double) queryStart,
                            "endTime", (double) queryEnd
                    )
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchCumulativeDamageTimeline");

            JsonNode graphNode = root.path("data").path("reportData").path("report").path("graph");
            return parseToCumulative(graphNode, queryStart);

        } catch (Exception e) {
            log.error("[FFLogs] fetchCumulativeDamageTimeline failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * FFLogs graph JSON scalar을 파이트 기준 누적 데미지 타임라인으로 변환.
     * graph.data.series 에서 "Total" 시리즈(또는 첫 번째 시리즈)를 사용.
     *
     * @param graphNode        graph 필드의 JsonNode
     * @param fightStartOffset 리포트 기준 파이트 시작 오프셋(ms)
     * @return [[fightElapsedMs, cumulativeDamage], ...]
     */
    private List<long[]> parseToCumulative(JsonNode graphNode, long fightStartOffset) {
        JsonNode series = graphNode.path("data").path("series");
        if (!series.isArray() || series.isEmpty()) {
            log.warn("[FFLogs] no series in graph response");
            return List.of();
        }

        // "Total" 시리즈 우선, 없으면 첫 번째 시리즈
        JsonNode targetSeries = null;
        for (JsonNode s : series) {
            String name = s.path("name").asText("");
            if ("Total".equalsIgnoreCase(name) || "All".equalsIgnoreCase(name)) {
                targetSeries = s;
                break;
            }
        }
        if (targetSeries == null) targetSeries = series.get(0);

        JsonNode dataArr = targetSeries.path("data");
        if (!dataArr.isArray() || dataArr.isEmpty()) {
            log.warn("[FFLogs] series data is empty");
            return List.of();
        }

        List<long[]> result = new ArrayList<>();
        result.add(new long[]{0L, 0L}); // 파이트 시작: elapsed=0, damage=0

        long cumulativeDamage = 0;
        long prevXMs = fightStartOffset;

        for (JsonNode point : dataArr) {
            if (!point.isArray() || point.size() < 2) continue;
            long xMs = point.get(0).asLong();
            double dps = point.get(1).asDouble();

            long bucketDurationMs = xMs - prevXMs;
            if (bucketDurationMs <= 0) { prevXMs = xMs; continue; }

            // 구간 데미지 = DPS × 구간길이(초)
            long bucketDamage = (long) (dps * bucketDurationMs / 1000.0);
            cumulativeDamage += bucketDamage;

            long elapsedMs = xMs - fightStartOffset;
            result.add(new long[]{elapsedMs, cumulativeDamage});
            prevXMs = xMs;
        }

        log.info("[FFLogs] timeline: {} points, total={}", result.size(), cumulativeDamage);
        return result;
    }

    private void checkErrors(JsonNode root, String context) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            log.warn("[FFLogs] {} GraphQL errors: {}", context, errors);
        }
    }

    public record TopRanking(String reportCode, long reportStartMs, long fightStartMs, long durationMs) {}
}