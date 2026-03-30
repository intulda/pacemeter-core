package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Guaranteed crit/direct-hit classification rules loaded from resource catalog.
 */
public final class AutoHitCatalog {

    private static final String RESOURCE = "auto-hit-catalog.json";
    private static final List<Entry> ENTRIES = loadEntries();

    private AutoHitCatalog() {
    }

    public static Optional<CombatEvent.HitOutcomeContext> resolve(
            int jobId,
            int actionId,
            String actionName,
            Set<ActionTagCatalog.ActionTag> actionTags,
            Set<Integer> activeSelfBuffIds,
            Set<String> activeSelfBuffNames
    ) {
        for (Entry entry : ENTRIES) {
            if (entry.jobId() != jobId) {
                continue;
            }
            if (!entry.matchesAction(actionId, actionName, actionTags)) {
                continue;
            }
            if (!entry.matchesRequiredBuffs(activeSelfBuffIds, activeSelfBuffNames)) {
                continue;
            }
            return Optional.of(entry.hitOutcomeContext());
        }
        return Optional.empty();
    }

    private static List<Entry> loadEntries() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = AutoHitCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE);
            }
            List<CatalogEntry> entries = objectMapper.readValue(is, new TypeReference<>() {});
            return entries.stream().map(CatalogEntry::toEntry).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CatalogEntry(
            int jobId,
            List<Integer> actionIds,
            Set<String> actionAliases,
            Set<String> requiredActionTags,
            List<Integer> requiredSelfBuffIds,
            Set<String> requiredSelfBuffAliases,
            String autoCrit,
            String autoDirectHit
    ) {
        private Entry toEntry() {
            return new Entry(
                    jobId,
                    actionIds == null ? Set.of() : Set.copyOf(actionIds),
                    actionAliases == null ? Set.of() : actionAliases.stream().map(AutoHitCatalog::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    requiredActionTags == null
                            ? Set.of()
                            : requiredActionTags.stream()
                            .map(ActionTagCatalog.ActionTag::valueOf)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    requiredSelfBuffIds == null ? Set.of() : Set.copyOf(requiredSelfBuffIds),
                    requiredSelfBuffAliases == null ? Set.of() : requiredSelfBuffAliases.stream().map(AutoHitCatalog::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    new CombatEvent.HitOutcomeContext(
                            CombatEvent.AutoHitFlag.valueOf(autoCrit),
                            CombatEvent.AutoHitFlag.valueOf(autoDirectHit)
                    )
            );
        }
    }

    private record Entry(
            int jobId,
            Set<Integer> actionIds,
            Set<String> actionAliases,
            Set<ActionTagCatalog.ActionTag> requiredActionTags,
            Set<Integer> requiredSelfBuffIds,
            Set<String> requiredSelfBuffAliases,
            CombatEvent.HitOutcomeContext hitOutcomeContext
    ) {
        private boolean matchesAction(int actionId, String actionName, Set<ActionTagCatalog.ActionTag> actionTags) {
            if (actionIds.contains(actionId)) {
                return true;
            }
            String normalizedName = normalize(actionName);
            if (!normalizedName.isBlank() && actionAliases.contains(normalizedName)) {
                return true;
            }
            return !requiredActionTags.isEmpty() && requiredActionTags.stream().allMatch(actionTags::contains);
        }

        private boolean matchesRequiredBuffs(Set<Integer> activeSelfBuffIds, Set<String> activeSelfBuffNames) {
            if (requiredSelfBuffIds.isEmpty() && requiredSelfBuffAliases.isEmpty()) {
                return true;
            }
            boolean idMatch = requiredSelfBuffIds.stream().anyMatch(activeSelfBuffIds::contains);
            boolean aliasMatch = requiredSelfBuffAliases.stream().anyMatch(activeSelfBuffNames::contains);
            return idMatch || aliasMatch;
        }
    }
}
