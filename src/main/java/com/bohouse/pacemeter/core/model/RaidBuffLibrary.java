package com.bohouse.pacemeter.core.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * rDPS 재분배에 사용할 레이드 버프 정의.
 *
 * 정의는 classpath의 {@code raid-buff-catalog.json}에서 로드한다.
 * 이 카탈로그는 외부 메타데이터(XIVAPI 등)로 ID/이름을 수집하고,
 * 우리 쪽에서 rDPS 의미가 있는 효과(kind/amount)를 유지하는 구조를 전제로 한다.
 */
public final class RaidBuffLibrary {

    private static final String RESOURCE = "raid-buff-catalog.json";
    private static final List<RaidBuffDefinition> DEFINITIONS = loadDefinitions();
    private static final Map<BuffId, RaidBuffDefinition> BY_ID = buildIdIndex();
    private static final Map<String, RaidBuffDefinition> BY_NAME = buildNameIndex();

    private RaidBuffLibrary() {
    }

    public static Optional<RaidBuffDefinition> find(BuffId buffId, String buffName) {
        RaidBuffDefinition byId = BY_ID.get(buffId);
        if (byId != null) {
            return Optional.of(byId);
        }
        if (buffName == null || buffName.isBlank()) {
            return Optional.empty();
        }
        RaidBuffDefinition byName = BY_NAME.get(normalize(buffName));
        return byName != null ? Optional.of(byName) : Optional.empty();
    }

    static List<RaidBuffDefinition> definitions() {
        return DEFINITIONS;
    }

    private static List<RaidBuffDefinition> loadDefinitions() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = RaidBuffLibrary.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE);
            }

            List<CatalogEntry> entries = objectMapper.readValue(is, new TypeReference<>() {});
            return entries.stream()
                    .map(CatalogEntry::toDefinition)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    private static Map<BuffId, RaidBuffDefinition> buildIdIndex() {
        java.util.HashMap<BuffId, RaidBuffDefinition> index = new java.util.HashMap<>();
        for (RaidBuffDefinition definition : DEFINITIONS) {
            for (BuffId id : definition.ids()) {
                index.put(id, definition);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, RaidBuffDefinition> buildNameIndex() {
        java.util.HashMap<String, RaidBuffDefinition> index = new java.util.HashMap<>();
        for (RaidBuffDefinition definition : DEFINITIONS) {
            for (String alias : definition.aliases()) {
                index.put(normalize(alias), definition);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record CatalogEntry(
            List<Integer> ids,
            List<CatalogEffect> effects,
            Set<String> aliases,
            String source
    ) {
        RaidBuffDefinition toDefinition() {
            Set<BuffId> buffIds = ids == null
                    ? Set.of()
                    : ids.stream().map(BuffId::new).collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<RaidBuffEffect> raidBuffEffects = effects == null
                    ? List.of()
                    : effects.stream().map(CatalogEffect::toEffect).toList();
            Set<String> normalizedAliases = aliases == null ? Set.of() : Set.copyOf(aliases);
            return new RaidBuffDefinition(buffIds, raidBuffEffects, normalizedAliases, source);
        }
    }

    private record CatalogEffect(String kind, double amount) {
        RaidBuffEffect toEffect() {
            return new RaidBuffEffect(RaidBuffEffect.Kind.valueOf(kind), amount);
        }
    }

    public record RaidBuffEffect(Kind kind, double amount) {
        public enum Kind {
            PERCENT_DAMAGE,
            CRIT_RATE,
            DIRECT_HIT_RATE
        }
    }

    public record RaidBuffDefinition(Set<BuffId> ids, List<RaidBuffEffect> effects, Set<String> aliases, String source) {
    }
}
