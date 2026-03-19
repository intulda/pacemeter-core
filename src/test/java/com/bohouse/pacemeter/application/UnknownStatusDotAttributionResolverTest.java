package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnknownStatusDotAttributionResolverTest {

    private final UnknownStatusDotAttributionResolver resolver = new UnknownStatusDotAttributionResolver();

    @Test
    void resolveTrackedStatusActionId_mapsStatusThroughMapper() {
        Instant now = Instant.parse("2026-03-19T00:00:10Z");
        DotTickRaw dot = new DotTickRaw(now, 0x40000001L, "Boss", "DoT", 0, 0x10000001L, "Actor", 1000, "24|...");
        Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> statusApps = new HashMap<>();
        statusApps.put(
                new UnknownStatusDotAttributionResolver.DotKey(0x10000001L, 0x40000001L),
                new UnknownStatusDotAttributionResolver.DotApplication(0x0767, now.minusMillis(1000))
        );

        Integer actionId = resolver.resolveTrackedStatusActionId(dot, statusApps, 90_000L, statusId -> statusId == 0x0767 ? 0x409C : 0);
        assertEquals(0x409C, actionId);
    }

    @Test
    void resolveUnknownSourceAttribution_picksMostRecentCandidateForTarget() {
        Instant now = Instant.parse("2026-03-19T00:01:00Z");
        DotTickRaw dot = new DotTickRaw(now, 0x40000001L, "Boss", "DoT", 0, 0xE0000000L, "", 1200, "24|...");

        Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> actionApps = new HashMap<>();
        actionApps.put(
                new UnknownStatusDotAttributionResolver.DotKey(0x10000001L, 0x40000001L),
                new UnknownStatusDotAttributionResolver.DotApplication(0x4094, now.minusMillis(3000))
        );
        actionApps.put(
                new UnknownStatusDotAttributionResolver.DotKey(0x10000002L, 0x40000001L),
                new UnknownStatusDotAttributionResolver.DotApplication(0x409C, now.minusMillis(1000))
        );

        Optional<UnknownStatusDotAttributionResolver.UnknownSourceAttribution> attribution =
                resolver.resolveUnknownSourceAttribution(
                        dot,
                        actionApps,
                        Map.of(),
                        0xE0000000L,
                        90_000L,
                        sourceId -> true,
                        sourceId -> "S" + sourceId,
                        ignored -> 0
                );

        assertTrue(attribution.isPresent());
        assertEquals(0x10000002L, attribution.orElseThrow().sourceId());
        assertEquals(0x409C, attribution.orElseThrow().actionId());
    }

    @Test
    void resolveUnknownSourceAttribution_ignoresExpiredOrDifferentTargetEvidence() {
        Instant now = Instant.parse("2026-03-19T00:02:00Z");
        DotTickRaw dot = new DotTickRaw(now, 0x40000001L, "Boss", "DoT", 0, 0xE0000000L, "", 1200, "24|...");

        Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> actionApps = new HashMap<>();
        actionApps.put(
                new UnknownStatusDotAttributionResolver.DotKey(0x10000001L, 0x40000002L),
                new UnknownStatusDotAttributionResolver.DotApplication(0x4094, now.minusMillis(1000))
        );
        actionApps.put(
                new UnknownStatusDotAttributionResolver.DotKey(0x10000002L, 0x40000001L),
                new UnknownStatusDotAttributionResolver.DotApplication(0x409C, now.minusMillis(91_000))
        );

        Optional<UnknownStatusDotAttributionResolver.UnknownSourceAttribution> attribution =
                resolver.resolveUnknownSourceAttribution(
                        dot,
                        actionApps,
                        Map.of(),
                        0xE0000000L,
                        90_000L,
                        sourceId -> true,
                        sourceId -> "S" + sourceId,
                        ignored -> 0
                );

        assertTrue(attribution.isEmpty());
    }
}
