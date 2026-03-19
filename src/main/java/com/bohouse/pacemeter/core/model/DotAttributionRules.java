package com.bohouse.pacemeter.core.model;

import java.util.Map;
import java.util.Set;

public record DotAttributionRules(
        Set<Integer> unknownStatusDotJobWhitelist,
        Map<Integer, Set<Integer>> applicationActionsByJob,
        Map<Integer, Set<Integer>> statusIdsByJob,
        Map<Integer, Integer> statusToAction,
        Set<Integer> snapshotStatusIds
) {
    public static DotAttributionRules fromCatalog() {
        return new DotAttributionRules(
                DotAttributionCatalog.unknownStatusDotJobWhitelist(),
                DotAttributionCatalog.applicationActionsByJob(),
                DotAttributionCatalog.statusIdsByJob(),
                DotAttributionCatalog.statusToAction(),
                DotAttributionCatalog.snapshotStatusIds()
        );
    }
}
