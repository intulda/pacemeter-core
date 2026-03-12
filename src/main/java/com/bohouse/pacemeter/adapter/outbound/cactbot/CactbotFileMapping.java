package com.bohouse.pacemeter.adapter.outbound.cactbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ACT territory ID를 cactbot 타임라인 파일 경로로 매핑한다.
 */
@Component
public class CactbotFileMapping {

    private static final Logger log = LoggerFactory.getLogger(CactbotFileMapping.class);
    private static final String RESOURCE = "cactbot-timelines.json";

    private final Map<Integer, String> mappings;

    public CactbotFileMapping(ObjectMapper objectMapper) {
        this.mappings = loadMappings(objectMapper);
        log.info("[Cactbot] loaded {} territory→timeline mappings", mappings.size());
    }

    public Optional<String> resolveTimelinePath(int territoryId) {
        return Optional.ofNullable(mappings.get(territoryId));
    }

    private Map<Integer, String> loadMappings(ObjectMapper objectMapper) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warn("[Cactbot] {} not found in classpath", RESOURCE);
                return Map.of();
            }

            TypeReference<Map<String, Object>> ref = new TypeReference<>() {};
            Map<String, Object> raw = objectMapper.readValue(is, ref);
            Map<Integer, String> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey().startsWith("_")) continue;
                if (!(entry.getValue() instanceof String path) || path.isBlank()) continue;
                try {
                    result.put(Integer.parseInt(entry.getKey()), path);
                } catch (NumberFormatException ignored) {
                    log.warn("[Cactbot] skipping non-numeric key: '{}'", entry.getKey());
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            log.error("[Cactbot] failed to load {}: {}", RESOURCE, e.getMessage(), e);
            return Map.of();
        }
    }
}
