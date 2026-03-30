package com.bohouse.pacemeter.core.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lightweight action metadata catalog for tags such as WEAPONSKILL.
 */
public final class ActionTagCatalog {

    private static final String RESOURCE = "action-tag-catalog.json";
    private static final Map<Integer, Set<ActionTag>> TAGS_BY_ACTION_ID = loadTagsByActionId();

    private ActionTagCatalog() {
    }

    public static Set<ActionTag> tagsFor(int actionId) {
        return TAGS_BY_ACTION_ID.getOrDefault(actionId, Set.of());
    }

    private static Map<Integer, Set<ActionTag>> loadTagsByActionId() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = ActionTagCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE);
            }
            List<CatalogEntry> entries = objectMapper.readValue(is, new TypeReference<>() {});
            Map<Integer, Set<ActionTag>> map = new HashMap<>();
            for (CatalogEntry entry : entries) {
                Set<ActionTag> tags = entry.tags() == null
                        ? Set.of()
                        : entry.tags().stream().map(ActionTag::valueOf).collect(Collectors.toUnmodifiableSet());
                for (Integer actionId : entry.actionIds() == null ? List.<Integer>of() : entry.actionIds()) {
                    map.put(actionId, tags);
                }
            }
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    public enum ActionTag {
        WEAPONSKILL
    }

    private record CatalogEntry(
            List<Integer> actionIds,
            Set<String> tags
    ) {
    }
}
