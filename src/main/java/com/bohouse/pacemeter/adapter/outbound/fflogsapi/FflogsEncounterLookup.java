package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ACT Zone name → FFLogs encounter ID 매핑.
 * src/main/resources/ffxiv-encounters.json 에서 로딩한다.
 * 키가 "_"로 시작하는 항목은 문서용 메타데이터로 무시한다.
 */
@Component
public class FflogsEncounterLookup {

    private static final Logger log = LoggerFactory.getLogger(FflogsEncounterLookup.class);
    private static final String RESOURCE = "ffxiv-encounters.json";

    private final Map<String, Integer> encounterMap;

    public FflogsEncounterLookup(ObjectMapper objectMapper) {
        Map<String, Integer> loaded = Map.of();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warn("[FFLogs] {} not found in classpath", RESOURCE);
            } else {
                // Object로 읽어서 직접 변환 (JSON에 _doc 키가 String 값을 가질 수 있으므로)
                TypeReference<Map<String, Object>> ref = new TypeReference<>() {};
                Map<String, Object> raw = objectMapper.readValue(is, ref);
                Map<String, Integer> result = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (entry.getKey().startsWith("_")) continue;
                    if (entry.getValue() instanceof Number n) {
                        result.put(entry.getKey(), n.intValue());
                    }
                }
                loaded = Map.copyOf(result);
                log.info("[FFLogs] loaded {} encounter mappings", loaded.size());
            }
        } catch (Exception e) {
            log.error("[FFLogs] failed to load {}: {}", RESOURCE, e.getMessage());
        }
        this.encounterMap = loaded;
    }

    /**
     * 존 이름으로 FFLogs encounter ID를 찾는다.
     * 0 이하인 경우 미설정으로 간주하여 empty를 반환한다.
     */
    public Optional<Integer> findEncounterId(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return Optional.empty();
        Integer id = encounterMap.get(zoneName.trim());
        if (id == null || id <= 0) return Optional.empty();
        return Optional.of(id);
    }
}