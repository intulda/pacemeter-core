package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    @Value("${pacemeter.fflogs.partition:}")
    private String defaultPartition;

    public FflogsApiClient(FflogsTokenStore tokenStore, ObjectMapper objectMapper) {
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create(API_URL);
    }

    public boolean isConfigured() {
        return tokenStore.isConfigured();
    }

    /**
     * 해당 인카운터의 rDPS 1위 랭킹 정보를 가져온다.
     *
     * @param encounterId FFLogs 인카운터 ID
     * @param className   직업 필터 (예: "Dancer"). null이면 전체 직업
     * @return 1위 파이트 정보 (reportCode, reportStartMs, fightStartMs, durationMs)
     */
    public Optional<TopRanking> fetchTopRanking(int encounterId, String className) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchTopRanking skipped - no token");
            return Optional.empty();
        }

        // className이 있으면 직업 필터 추가
        String query;
        Map<String, Object> variables;

        String partition = effectivePartition();

        if (className != null && !className.isBlank() && partition != null) {
            query = """
                    query($encounterId: Int!, $className: String!, $partition: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, className: $className, partition: $partition, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "className", className,
                    "partition", partition
            );
        } else if (className != null && !className.isBlank()) {
            query = """
                    query($encounterId: Int!, $className: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, className: $className, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "className", className
            );
        } else if (partition != null) {
            query = """
                    query($encounterId: Int!, $partition: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, partition: $partition, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "partition", partition
            );
        } else {
            query = """
                    query($encounterId: Int!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId
            );
        }

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", variables
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
            String playerName = r.path("name").asText("");
            // characterRankings 응답에는 actorID가 없으므로 0으로 초기화 → 호출자가 fetchPlayerSourceId로 조회
            int sourceId = r.path("actorID").asInt(0);

            if (reportCode.isBlank() || durationMs <= 0) {
                log.warn("[FFLogs] invalid ranking data: code='{}' duration={}", reportCode, durationMs);
                return Optional.empty();
            }

            log.info("[FFLogs] top ranking: name={} rdps={} duration={}ms code={} sourceId={} partition={}",
                    playerName, (long) amount, durationMs, reportCode, sourceId, partition != null ? partition : "GLOBAL");

            return Optional.of(new TopRanking(reportCode, reportStartMs, fightStartMs, durationMs, sourceId, playerName));

        } catch (Exception e) {
            log.error("[FFLogs] fetchTopRanking failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 특정 플레이어의 개인 DPS 타임라인을 가져온다.
     *
     * @param reportCode    FFLogs 리포트 코드
     * @param reportStartMs 리포트 시작 epoch ms
     * @param fightStartMs  파이트 시작 epoch ms
     * @param durationMs    파이트 지속 시간 ms
     * @param sourceId      플레이어 sourceID (rankings의 actorID)
     * @return [[elapsedMs, cumulativeDamage], ...] 개인 누적 DPS 타임라인
     */
    public List<long[]> fetchIndividualDamageTimeline(
            String reportCode, long reportStartMs, long fightStartMs, long durationMs, int sourceId) {

        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) return List.of();

        long queryStart = fightStartMs - reportStartMs;
        long queryEnd = queryStart + durationMs;

        // sourceID 필터 추가: 특정 플레이어만
        String query = """
                query($code: String!, $startTime: Float!, $endTime: Float!, $sourceId: Int!) {
                  reportData {
                    report(code: $code) {
                      graph(
                        startTime: $startTime
                        endTime: $endTime
                        dataType: DamageDone
                        sourceID: $sourceId
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
                            "endTime", (double) queryEnd,
                            "sourceId", sourceId
                    )
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchIndividualDamageTimeline");

            JsonNode graphNode = root.path("data").path("reportData").path("report").path("graph");
            List<long[]> timeline = parseToCumulative(graphNode, queryStart);

            log.info("[FFLogs] individual timeline: {} points for sourceId={}", timeline.size(), sourceId);
            return timeline;

        } catch (Exception e) {
            log.error("[FFLogs] fetchIndividualDamageTimeline failed: {}", e.getMessage());
            return List.of();
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
     * FFLogs graph scalar 응답을 파이트 기준 누적 데미지 타임라인으로 변환.
     *
     * 실제 응답 구조:
     * {
     *   "startTime": ..., "endTime": ...,
     *   "series": [{
     *     "name": "Total",
     *     "pointStart": <ms from report start>,
     *     "pointInterval": <ms per bucket>,
     *     "data": [dps0, dps1, dps2, ...]   ← scalar DPS 값 (NOT [x,y] 쌍)
     *   }]
     * }
     */
    private List<long[]> parseToCumulative(JsonNode graphNode, long fightStartOffset) {
        // FFLogs graph 필드는 JSON scalar (문자열) 타입 → 내부 JSON 재파싱 필요
        if (graphNode.isTextual()) {
            try {
                graphNode = objectMapper.readTree(graphNode.asText());
                log.info("[FFLogs] graph scalar parsed successfully");
            } catch (Exception e) {
                log.error("[FFLogs] failed to parse graph scalar: {}", e.getMessage());
                return List.of();
            }
        }

        // 실제 응답 구조 확인 (graph 루트 키 목록)
        List<String> rootKeys = new ArrayList<>();
        graphNode.fieldNames().forEachRemaining(rootKeys::add);
        log.info("[FFLogs] graph root keys: {}", rootKeys);

        // series 경로 탐색: 루트 직접 또는 data 아래
        JsonNode series = graphNode.path("series");
        if (!series.isArray() || series.isEmpty()) {
            series = graphNode.path("data").path("series");
        }
        if (!series.isArray() || series.isEmpty()) {
            log.warn("[FFLogs] no series in graph response. graphNode={}", graphNode.toString().substring(0, Math.min(500, graphNode.toString().length())));
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

        // pointStart/pointInterval로 각 버킷의 타임스탬프 계산
        long pointStart = targetSeries.path("pointStart").asLong(fightStartOffset);
        double pointInterval = targetSeries.path("pointInterval").asDouble(1000.0);
        if (pointInterval <= 0) pointInterval = 1000.0;

        log.info("[FFLogs] parsing timeline: points={} pointStart={} pointInterval={}ms",
                dataArr.size(), pointStart, (long) pointInterval);

        List<long[]> result = new ArrayList<>();
        result.add(new long[]{0L, 0L}); // 파이트 시작: elapsed=0, damage=0

        long cumulativeDamage = 0;

        for (int i = 0; i < dataArr.size(); i++) {
            double dps = dataArr.get(i).asDouble();

            // 버킷 타임스탬프 (리포트 기준 ms)
            long xMs = pointStart + (long) (i * pointInterval);
            // 구간 데미지 = DPS × pointInterval(초)
            long bucketDamage = (long) (dps * pointInterval / 1000.0);
            cumulativeDamage += bucketDamage;

            long elapsedMs = xMs - fightStartOffset;
            result.add(new long[]{elapsedMs, cumulativeDamage});
        }

        log.info("[FFLogs] timeline built: {} points, totalDamage={}", result.size(), cumulativeDamage);
        return result;
    }

    private void checkErrors(JsonNode root, String context) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            log.warn("[FFLogs] {} GraphQL errors: {}", context, errors);
        }
    }

    private JsonNode parseScalarJson(JsonNode node, String fieldName) {
        if (node.isTextual()) {
            try {
                return objectMapper.readTree(node.asText());
            } catch (Exception e) {
                log.error("[FFLogs] failed to parse {} scalar JSON: {}", fieldName, e.getMessage());
                return objectMapper.createObjectNode();
            }
        }
        return node;
    }

    /**
     * masterData.actors에서 플레이어 이름으로 sourceId를 조회한다.
     * characterRankings 응답에 actorID가 없을 때 사용.
     *
     * @param reportCode 리포트 코드
     * @param playerName 플레이어 이름
     * @return sourceId (못 찾으면 0)
     */
    public int fetchPlayerSourceId(String reportCode, String playerName) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) return 0;

        String query = """
                query($code: String!) {
                  reportData {
                    report(code: $code) {
                      masterData {
                        actors {
                          id
                          name
                          type
                        }
                      }
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of("code", reportCode)
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchPlayerSourceId");

            JsonNode actors = root.path("data").path("reportData").path("report")
                    .path("masterData").path("actors");

            if (!actors.isArray()) {
                log.warn("[FFLogs] masterData.actors not found");
                return 0;
            }

            for (JsonNode actor : actors) {
                String type = actor.path("type").asText("");
                String name = actor.path("name").asText("");
                if ("Player".equalsIgnoreCase(type) && playerName.equalsIgnoreCase(name)) {
                    int id = actor.path("id").asInt(0);
                    log.info("[FFLogs] resolved sourceId={} for player '{}'", id, playerName);
                    return id;
                }
            }

            log.warn("[FFLogs] player '{}' not found in masterData.actors", playerName);
            return 0;

        } catch (Exception e) {
            log.error("[FFLogs] fetchPlayerSourceId failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * FFLogs zone ID로 해당 존의 모든 encounter 목록을 가져온다.
     * ACT의 zoneId와 FFLogs의 zone ID는 FFXIV 내부 ID로 동일하다.
     *
     * @param zoneId ACT ZoneChanged의 zone ID (decimal)
     * @return encounter 목록. API 오류 시 빈 리스트
     */
    public List<EncounterInfo> fetchZoneEncounters(int zoneId) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchZoneEncounters skipped - no token");
            return List.of();
        }

        String query = """
                query($zoneId: Int!) {
                  worldData {
                    zone(id: $zoneId) {
                      encounters {
                        id
                        name
                      }
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of("zoneId", zoneId)
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchZoneEncounters");

            JsonNode encountersNode = root.path("data").path("worldData")
                    .path("zone").path("encounters");

            if (!encountersNode.isArray() || encountersNode.isEmpty()) {
                log.warn("[FFLogs] no encounters for zoneId={}", zoneId);
                return List.of();
            }

            List<EncounterInfo> result = new ArrayList<>();
            for (JsonNode e : encountersNode) {
                int id = e.path("id").asInt(0);
                String name = e.path("name").asText("");
                if (id > 0) result.add(new EncounterInfo(id, name));
            }

            log.info("[FFLogs] zone={} has {} encounters: {}", zoneId, result.size(), result);
            return result;

        } catch (Exception e) {
            log.error("[FFLogs] fetchZoneEncounters failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<TopRanking> fetchTopRankings(int encounterId, String className, int limit) {
        Optional<TopRanking> first = fetchTopRanking(encounterId, className);
        if (first.isEmpty()) {
            return List.of();
        }

        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            return List.of(first.get());
        }

        String query;
        Map<String, Object> variables;
        String partition = effectivePartition();

        if (className != null && !className.isBlank() && partition != null) {
            query = """
                    query($encounterId: Int!, $className: String!, $partition: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, className: $className, partition: $partition, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "className", className,
                    "partition", partition
            );
        } else if (className != null && !className.isBlank()) {
            query = """
                    query($encounterId: Int!, $className: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, className: $className, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "className", className
            );
        } else if (partition != null) {
            query = """
                    query($encounterId: Int!, $partition: String!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, partition: $partition, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of(
                    "encounterId", encounterId,
                    "partition", partition
            );
        } else {
            query = """
                    query($encounterId: Int!) {
                      worldData {
                        encounter(id: $encounterId) {
                          characterRankings(metric: rdps, page: 1)
                        }
                      }
                    }
                    """;
            variables = Map.of("encounterId", encounterId);
        }

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", variables
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchTopRankings");

            JsonNode rankings = root.path("data").path("worldData")
                    .path("encounter").path("characterRankings").path("rankings");

            if (!rankings.isArray() || rankings.isEmpty()) {
                return List.of(first.get());
            }

            List<TopRanking> result = new ArrayList<>();
            int count = Math.min(Math.max(limit, 1), rankings.size());
            for (int i = 0; i < count; i++) {
                JsonNode r = rankings.get(i);
                String reportCode = r.path("report").path("code").asText("");
                long reportStartMs = r.path("report").path("startTime").asLong(0);
                long fightStartMs = r.path("startTime").asLong(0);
                long durationMs = r.path("duration").asLong(0);
                double amount = r.path("amount").asDouble(0);
                String playerName = r.path("name").asText("");
                int sourceId = r.path("actorID").asInt(0);

                if (reportCode.isBlank() || durationMs <= 0) {
                    continue;
                }

                log.info("[FFLogs] top ranking[{}]: name={} rdps={} duration={}ms code={} sourceId={} partition={}",
                        i, playerName, (long) amount, durationMs, reportCode, sourceId, partition != null ? partition : "GLOBAL");
                result.add(new TopRanking(reportCode, reportStartMs, fightStartMs, durationMs, sourceId, playerName));
            }

            return result.isEmpty() ? List.of(first.get()) : result;
        } catch (Exception e) {
            log.error("[FFLogs] fetchTopRankings failed: {}", e.getMessage());
            return List.of(first.get());
        }
    }

    private String effectivePartition() {
        if (defaultPartition == null || defaultPartition.isBlank()) {
            return null;
        }
        return defaultPartition;
    }

    public Optional<ReportSummary> fetchReportSummary(String reportCode) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchReportSummary skipped - no token");
            return Optional.empty();
        }

        String query = """
                query($code: String!) {
                  reportData {
                    report(code: $code) {
                      code
                      startTime
                      fights {
                        id
                        name
                        startTime
                        endTime
                        kill
                        encounterID
                      }
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of("code", reportCode)
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchReportSummary");

            JsonNode reportNode = root.path("data").path("reportData").path("report");
            if (reportNode.isMissingNode() || reportNode.isNull()) {
                log.warn("[FFLogs] report summary missing for code={}", reportCode);
                return Optional.empty();
            }

            String code = reportNode.path("code").asText(reportCode);
            long startTime = reportNode.path("startTime").asLong(0L);
            JsonNode fightsNode = reportNode.path("fights");

            List<ReportFight> fights = new ArrayList<>();
            if (fightsNode.isArray()) {
                for (JsonNode fightNode : fightsNode) {
                    int id = fightNode.path("id").asInt(0);
                    String name = fightNode.path("name").asText("");
                    long fightStartTime = fightNode.path("startTime").asLong(0L);
                    long endTime = fightNode.path("endTime").asLong(0L);
                    boolean kill = fightNode.path("kill").asBoolean(false);
                    int encounterId = fightNode.path("encounterID").asInt(0);

                    fights.add(new ReportFight(id, name, fightStartTime, endTime, kill, encounterId));
                }
            }

            return Optional.of(new ReportSummary(code, startTime, fights));
        } catch (Exception e) {
            log.error("[FFLogs] fetchReportSummary failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<DamageDoneEntry> fetchDamageDoneTable(String reportCode, int fightId) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchDamageDoneTable skipped - no token");
            return List.of();
        }

        String query = """
                query($code: String!, $fightId: Int!) {
                  reportData {
                    report(code: $code) {
                      table(dataType: DamageDone, fightIDs: [$fightId])
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of(
                            "code", reportCode,
                            "fightId", fightId
                    )
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchDamageDoneTable");

            JsonNode tableNode = root.path("data").path("reportData").path("report").path("table");
            tableNode = parseScalarJson(tableNode, "table");

            JsonNode entriesNode = tableNode.path("entries");
            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                entriesNode = tableNode.path("data").path("entries");
            }
            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                log.warn("[FFLogs] damage table has no entries for code={} fightId={}", reportCode, fightId);
                return List.of();
            }

            List<DamageDoneEntry> entries = new ArrayList<>();
            for (JsonNode entryNode : entriesNode) {
                entries.add(new DamageDoneEntry(
                        entryNode.path("id").isMissingNode() ? null : entryNode.path("id").asInt(),
                        entryNode.path("name").asText(""),
                        entryNode.path("type").asText(""),
                        entryNode.path("icon").asText(""),
                        entryNode.path("total").asDouble(0.0),
                        entryNode.path("activeTime").asDouble(0.0),
                        entryNode.path("totalRDPS").asDouble(0.0),
                        entryNode.path("totalRDPSTaken").asDouble(0.0),
                        entryNode.path("totalRDPSGiven").asDouble(0.0)
                ));
            }
            return entries;
        } catch (Exception e) {
            log.error("[FFLogs] fetchDamageDoneTable failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AbilityDamageEntry> fetchDamageDoneAbilities(String reportCode, int fightId, int sourceId) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchDamageDoneAbilities skipped - no token");
            return List.of();
        }

        String query = """
                query($code: String!, $fightId: Int!, $sourceId: Int!) {
                  reportData {
                    report(code: $code) {
                      table(dataType: DamageDone, fightIDs: [$fightId], sourceID: $sourceId, viewBy: Ability)
                    }
                  }
                }
                """;

        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "query", query,
                    "variables", Map.of(
                            "code", reportCode,
                            "fightId", fightId,
                            "sourceId", sourceId
                    )
            ));

            String response = restClient.post()
                    .header("Authorization", "Bearer " + token.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            checkErrors(root, "fetchDamageDoneAbilities");

            JsonNode tableNode = root.path("data").path("reportData").path("report").path("table");
            tableNode = parseScalarJson(tableNode, "table");

            JsonNode entriesNode = tableNode.path("entries");
            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                entriesNode = tableNode.path("data").path("entries");
            }
            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                log.warn("[FFLogs] damage ability table has no entries for code={} fightId={} sourceId={}",
                        reportCode, fightId, sourceId);
                return List.of();
            }

            List<AbilityDamageEntry> entries = new ArrayList<>();
            for (JsonNode entryNode : entriesNode) {
                entries.add(new AbilityDamageEntry(
                        entryNode.path("guid").isMissingNode() ? null : entryNode.path("guid").asInt(),
                        entryNode.path("name").asText(""),
                        entryNode.path("total").asDouble(0.0),
                        entryNode.path("type").asText("")
                ));
            }
            return entries;
        } catch (Exception e) {
            log.error("[FFLogs] fetchDamageDoneAbilities failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<DamageEventEntry> fetchDamageDoneEventsByAbility(
            String reportCode,
            int fightId,
            int sourceId,
            int abilityId
    ) {
        return fetchDamageDoneEventsInternal(reportCode, fightId, sourceId, abilityId);
    }

    public List<DamageEventEntry> fetchDamageDoneEvents(
            String reportCode,
            int fightId,
            int sourceId
    ) {
        return fetchDamageDoneEventsInternal(reportCode, fightId, sourceId, null);
    }

    private List<DamageEventEntry> fetchDamageDoneEventsInternal(
            String reportCode,
            int fightId,
            int sourceId,
            Integer abilityId
    ) {
        Optional<String> token = tokenStore.getToken();
        if (token.isEmpty()) {
            log.warn("[FFLogs] fetchDamageDoneEvents skipped - no token");
            return List.of();
        }

        String query = abilityId == null
                ? """
                query($code: String!, $fightId: Int!, $sourceId: Int!, $startTime: Float) {
                  reportData {
                    report(code: $code) {
                      events(
                        dataType: DamageDone
                        fightIDs: [$fightId]
                        sourceID: $sourceId
                        startTime: $startTime
                      ) {
                        data
                        nextPageTimestamp
                      }
                    }
                  }
                }
                """
                : """
                query($code: String!, $fightId: Int!, $sourceId: Int!, $abilityId: Float!, $startTime: Float) {
                  reportData {
                    report(code: $code) {
                      events(
                        dataType: DamageDone
                        fightIDs: [$fightId]
                        sourceID: $sourceId
                        abilityID: $abilityId
                        startTime: $startTime
                      ) {
                        data
                        nextPageTimestamp
                      }
                    }
                  }
                }
                """;

        List<DamageEventEntry> events = new ArrayList<>();
        Double nextStartTime = null;
        try {
            while (true) {
                Map<String, Object> variables = new java.util.HashMap<>();
                variables.put("code", reportCode);
                variables.put("fightId", fightId);
                variables.put("sourceId", sourceId);
                if (abilityId != null) {
                    variables.put("abilityId", (double) abilityId);
                }
                if (nextStartTime != null) {
                    variables.put("startTime", nextStartTime);
                }
                byte[] body = objectMapper.writeValueAsBytes(Map.of(
                        "query", query,
                        "variables", variables
                ));

                String response = restClient.post()
                        .header("Authorization", "Bearer " + token.get())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                checkErrors(root, abilityId == null
                        ? "fetchDamageDoneEvents"
                        : "fetchDamageDoneEventsByAbility");

                JsonNode eventsNode = root.path("data").path("reportData").path("report").path("events");
                eventsNode = parseScalarJson(eventsNode, "events");
                JsonNode dataNode = eventsNode.path("data");
                if (dataNode.isArray()) {
                    for (JsonNode eventNode : dataNode) {
                        long amount = eventNode.path("amount").asLong(0L);
                        if (amount <= 0L) {
                            continue;
                        }
                        events.add(new DamageEventEntry(
                                eventNode.path("timestamp").asLong(0L),
                                eventNode.path("sourceID").asInt(0),
                                eventNode.path("targetID").asInt(0),
                                eventNode.path("abilityGameID").asInt(0),
                                amount,
                                eventNode.path("hitType").isMissingNode() ? null : eventNode.path("hitType").asInt()
                        ));
                    }
                }

                JsonNode nextPageTimestampNode = eventsNode.path("nextPageTimestamp");
                if (!nextPageTimestampNode.isNumber()) {
                    break;
                }
                double candidate = nextPageTimestampNode.asDouble(Double.NaN);
                if (!Double.isFinite(candidate) || candidate <= 0.0) {
                    break;
                }
                if (nextStartTime != null && candidate <= nextStartTime) {
                    break;
                }
                nextStartTime = candidate;
            }
            return events;
        } catch (Exception e) {
            log.error("[FFLogs] fetchDamageDoneEvents failed: {}", e.getMessage());
            return List.of();
        }
    }

    public record TopRanking(String reportCode, long reportStartMs, long fightStartMs, long durationMs, int sourceId, String playerName) {}

    public record EncounterInfo(int id, String name) {}

    public record ReportSummary(String reportCode, long startTime, List<ReportFight> fights) {}

    public record ReportFight(int id, String name, long startTime, long endTime, boolean kill, int encounterId) {}

    public record DamageDoneEntry(
            Integer id,
            String name,
            String type,
            String icon,
            double total,
            double activeTime,
            double totalRdps,
            double totalRdpsTaken,
            double totalRdpsGiven
    ) {}

    public record AbilityDamageEntry(
            Integer guid,
            String name,
            double total,
            String type
    ) {}

    public record DamageEventEntry(
            long timestamp,
            int sourceId,
            int targetId,
            int abilityGameId,
            long amount,
            Integer hitType
    ) {}
}
