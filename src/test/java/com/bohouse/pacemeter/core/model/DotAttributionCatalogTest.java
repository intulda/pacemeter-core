package com.bohouse.pacemeter.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DotAttributionCatalogTest {

    @Test
    void catalog_hasStableTrackedJobSetAndConsistentSnapshotStatusIds() {
        Set<Integer> trackedJobs = DotAttributionCatalog.unknownStatusDotJobWhitelist();
        assertEquals(Set.of(19, 22, 24, 28, 30, 33, 34, 37, 40), trackedJobs);

        Map<Integer, Integer> statusToAction = DotAttributionCatalog.statusToAction();
        assertEquals(statusToAction.keySet(), DotAttributionCatalog.snapshotStatusIds());
    }

    @Test
    void catalog_statusMappings_areBackedByJobStatusLists() {
        Map<Integer, Set<Integer>> statusesByJob = DotAttributionCatalog.statusIdsByJob();
        Set<Integer> allJobStatuses = statusesByJob.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        for (Integer statusId : DotAttributionCatalog.statusToAction().keySet()) {
            assertTrue(
                    allJobStatuses.contains(statusId),
                    "status_id " + statusId + " must exist in job status_ids"
            );
        }
    }

    @Test
    void catalog_excludesAutoAttackActionIdsFromDotMappings() {
        Map<Integer, Integer> statusToAction = DotAttributionCatalog.statusToAction();
        assertTrue(!statusToAction.containsValue(0x7));
        assertTrue(!statusToAction.containsValue(0x17));

        Map<Integer, Set<Integer>> actionsByJob = DotAttributionCatalog.applicationActionsByJob();
        for (Set<Integer> actionIds : actionsByJob.values()) {
            assertTrue(!actionIds.contains(0x7));
            assertTrue(!actionIds.contains(0x17));
        }
    }

    @Test
    void catalog_confirmsSamuraiHiganbanaAndExcludesFeintStatus() {
        // Higanbana: status 0x04CC -> action 0x1D41 (Samurai)
        assertEquals(0x1D41, DotAttributionCatalog.statusToAction().get(0x04CC));
        assertTrue(DotAttributionCatalog.statusIdsByJob().getOrDefault(34, Set.of()).contains(0x04CC));

        // Feint: status 0x04AB (JobRole) is not a DoT and must never be treated as DoT attribution status.
        assertTrue(!DotAttributionCatalog.statusToAction().containsKey(0x04AB));
        assertTrue(!DotAttributionCatalog.snapshotStatusIds().contains(0x04AB));
    }
}
