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
 * statusId=0 DoT attribution용 카탈로그.
 *
 * classpath의 {@code dot-attribution-catalog.json}을 로드해
 * job별 unknown-status DoT 허용 조건과 status/action 매핑을 제공한다.
 */
public final class DotAttributionCatalog {

    private static final String RESOURCE = "dot-attribution-catalog.json";
    private static final List<Entry> ENTRIES = loadEntries();
    private static final Set<Integer> JOB_WHITELIST = buildJobWhitelist();
    private static final Map<Integer, Set<Integer>> APPLICATION_ACTIONS_BY_JOB = buildApplicationActionsByJob();
    private static final Map<Integer, Set<Integer>> STATUS_IDS_BY_JOB = buildStatusIdsByJob();
    private static final Map<Integer, Integer> STATUS_TO_ACTION = buildStatusToAction();
    private static final Map<Integer, Set<Integer>> ACTION_TO_STATUSES = buildActionToStatuses();
    private static final Set<Integer> SNAPSHOT_STATUS_IDS = STATUS_TO_ACTION.keySet();

    private DotAttributionCatalog() {
    }

    public static Set<Integer> unknownStatusDotJobWhitelist() {
        return JOB_WHITELIST;
    }

    public static Map<Integer, Set<Integer>> applicationActionsByJob() {
        return APPLICATION_ACTIONS_BY_JOB;
    }

    public static Map<Integer, Set<Integer>> statusIdsByJob() {
        return STATUS_IDS_BY_JOB;
    }

    public static Map<Integer, Integer> statusToAction() {
        return STATUS_TO_ACTION;
    }

    public static Set<Integer> statusIdsForAction(int actionId) {
        return ACTION_TO_STATUSES.getOrDefault(actionId, Set.of());
    }

    public static Set<Integer> snapshotStatusIds() {
        return SNAPSHOT_STATUS_IDS;
    }

    private static List<Entry> loadEntries() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream is = DotAttributionCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + RESOURCE);
            }
            List<CatalogEntry> entries = objectMapper.readValue(is, new TypeReference<>() {});
            return entries.stream()
                    .map(CatalogEntry::toEntry)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    private static Set<Integer> buildJobWhitelist() {
        return ENTRIES.stream()
                .map(Entry::jobId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<Integer, Set<Integer>> buildApplicationActionsByJob() {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (Entry entry : ENTRIES) {
            if (!entry.applicationActionIds().isEmpty()) {
                map.put(entry.jobId(), entry.applicationActionIds());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<Integer, Set<Integer>> buildStatusIdsByJob() {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (Entry entry : ENTRIES) {
            if (!entry.statusIds().isEmpty()) {
                map.put(entry.jobId(), entry.statusIds());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<Integer, Integer> buildStatusToAction() {
        Map<Integer, Integer> map = new HashMap<>();
        for (Entry entry : ENTRIES) {
            for (StatusActionMapping mapping : entry.statusToAction()) {
                map.put(mapping.statusId(), mapping.actionId());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<Integer, Set<Integer>> buildActionToStatuses() {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (Entry entry : ENTRIES) {
            for (StatusActionMapping mapping : entry.statusToAction()) {
                map.computeIfAbsent(mapping.actionId(), ignored -> new java.util.HashSet<>())
                        .add(mapping.statusId());
            }
        }
        return map.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    private record CatalogEntry(
            int jobId,
            List<Integer> applicationActionIds,
            List<Integer> statusIds,
            List<StatusActionCatalogEntry> statusToAction
    ) {
        Entry toEntry() {
            Set<Integer> applicationActions = applicationActionIds == null
                    ? Set.of()
                    : Set.copyOf(applicationActionIds);
            Set<Integer> statuses = statusIds == null
                    ? Set.of()
                    : Set.copyOf(statusIds);
            List<StatusActionMapping> mappings = statusToAction == null
                    ? List.of()
                    : statusToAction.stream()
                    .map(entry -> new StatusActionMapping(entry.statusId(), entry.actionId()))
                    .toList();
            return new Entry(jobId, applicationActions, statuses, mappings);
        }
    }

    private record StatusActionCatalogEntry(int statusId, int actionId) {
    }

    private record Entry(
            int jobId,
            Set<Integer> applicationActionIds,
            Set<Integer> statusIds,
            List<StatusActionMapping> statusToAction
    ) {
    }

    private record StatusActionMapping(int statusId, int actionId) {
    }
}
