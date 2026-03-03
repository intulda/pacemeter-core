package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * ACT territory ID → FFLogs zone ID + encounter index 변환.
 *
 * fflogs-zones.json에서 Savage territory ID만 읽는다.
 * 같은 zone 내에서 territory ID를 오름차순 정렬한 순서가 encounter index(0부터)가 된다.
 *
 * 예: zone 54(Anabaseios)에 [1148, 1150, 1152, 1154]
 *   1148(P9S)  → index=0
 *   1150(P10S) → index=1
 *   1152(P11S) → index=2
 *   1154(P12S) → index=3
 *
 * 새 레이드 시즌: fflogs-zones.json에 Savage territory ID 추가.
 * cactbot zone_id.ts에서 확인 가능.
 */
@Component
public class FflogsZoneLookup {

    private static final Logger log = LoggerFactory.getLogger(FflogsZoneLookup.class);
    private static final String RESOURCE = "fflogs-zones.json";

    // actTerritoryId → ZoneLookupResult (precomputed at startup)
    private final Map<Integer, ZoneLookupResult> resolvedMap;

    public FflogsZoneLookup(ObjectMapper objectMapper) {
        this.resolvedMap = buildResolvedMap(objectMapper);
        log.info("[FFLogs] loaded {} territory→encounter mappings", resolvedMap.size());
    }

    private Map<Integer, ZoneLookupResult> buildResolvedMap(ObjectMapper objectMapper) {
        // 1. JSON 읽기: actTerritoryId(string) → fflogsZoneId(int)
        Map<Integer, Integer> raw = loadRaw(objectMapper);
        if (raw.isEmpty()) return Map.of();

        // 2. fflogsZoneId → [actTerritoryId] 역방향 그룹핑
        Map<Integer, List<Integer>> zoneToTerritories = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : raw.entrySet()) {
            zoneToTerritories.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        // 3. 각 zone 내에서 territory ID 오름차순 정렬 → 순서가 encounter index
        Map<Integer, ZoneLookupResult> result = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : zoneToTerritories.entrySet()) {
            int fflogsZoneId = e.getKey();
            List<Integer> territories = e.getValue();
            Collections.sort(territories);
            for (int i = 0; i < territories.size(); i++) {
                int territoryId = territories.get(i);
                result.put(territoryId, new ZoneLookupResult(fflogsZoneId, i));
                log.debug("[FFLogs] mapped territory={} → zone={} encounterIndex={}", territoryId, fflogsZoneId, i);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<Integer, Integer> loadRaw(ObjectMapper objectMapper) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warn("[FFLogs] {} not found in classpath", RESOURCE);
                return Map.of();
            }
            TypeReference<Map<String, Object>> ref = new TypeReference<>() {};
            Map<String, Object> raw = objectMapper.readValue(is, ref);
            Map<Integer, Integer> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey().startsWith("_")) continue;
                if (entry.getValue() instanceof Number zoneId) {
                    try {
                        int territoryId = Integer.parseInt(entry.getKey());
                        result.put(territoryId, zoneId.intValue());
                    } catch (NumberFormatException ignored) {
                        log.warn("[FFLogs] skipping non-numeric key: '{}'", entry.getKey());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[FFLogs] failed to load {}: {}", RESOURCE, e.getMessage());
            return Map.of();
        }
    }

    /**
     * ACT territory ID로 FFLogs zone ID + encounter index를 조회한다.
     *
     * @param actTerritoryId ACT ZoneChanged에서 받은 zone ID (정수)
     * @return 매핑 결과. JSON에 없으면 empty
     */
    public Optional<ZoneLookupResult> resolve(int actTerritoryId) {
        return Optional.ofNullable(resolvedMap.get(actTerritoryId));
    }

    public record ZoneLookupResult(int fflogsZoneId, int encounterIndex) {}
}