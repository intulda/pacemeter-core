package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.estimator.TimelinePaceProfile;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.DamageType;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatServiceDebugTest {

    @Test
    void debugSnapshot_exposesCurrentPlayerBreakdown() {
        CombatService service = new CombatService(
                new CombatEngine(),
                snapshot -> { },
                new PaceProfileProvider() {
                    @Override
                    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId) {
                        return Optional.of(PaceProfile.NONE);
                    }
                },
                territoryId -> Optional.empty()
        );

        service.setCurrentPlayerId(new ActorId(1));
        service.onEvent(new CombatEvent.FightStart(0, "Test", 0, 0));
        service.onEvent(new CombatEvent.ActorJoined(0, new ActorId(1), "Player"));
        service.onEvent(new CombatEvent.ActorJoined(0, new ActorId(2), "Astrologian"));
        service.onEvent(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        service.onEvent(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Player",
                new ActorId(100),
                1,
                10_600,
                DamageType.DIRECT,
                false,
                false
        ));

        CombatDebugSnapshot snapshot = service.debugSnapshot();
        assertEquals("Test", snapshot.fightName());
        assertNotNull(snapshot.currentPlayer());
        assertTrue(snapshot.currentPlayer().currentPlayer());
        assertEquals("Player", snapshot.currentPlayer().name());
        assertEquals(10_600L, snapshot.currentPlayer().totalDamage());
        assertEquals(600.0, snapshot.currentPlayer().receivedBuffContribution(), 1.0);
        assertEquals(10_000.0, snapshot.currentPlayer().onlineRdps(), 1.0);
        assertEquals(2, snapshot.actors().size());
    }

    @Test
    void asyncProfileLoad_republishesSnapshotWithPartyPace() throws Exception {
        CountDownLatch republished = new CountDownLatch(1);
        List<OverlaySnapshot> snapshots = new ArrayList<>();
        PaceProfile topProfile = new TimelinePaceProfile(
                "FFLogs #1 rDPS: Test",
                10_000L,
                new long[]{0L, 10_000L},
                new long[]{0L, 100_000L}
        );

        CombatService service = new CombatService(
                new CombatEngine(),
                snapshot -> {
                    snapshots.add(snapshot);
                    if (snapshot.partyPace() != null) {
                        republished.countDown();
                    }
                },
                new PaceProfileProvider() {
                    @Override
                    public Optional<PaceProfile> findProfile(String fightName, int actTerritoryId, int playerJobId) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.of(topProfile);
                    }
                },
                territoryId -> Optional.empty()
        );

        service.onEvent(new CombatEvent.FightStart(0, "Test", 123, 0));
        service.onEvent(new CombatEvent.ActorJoined(0, new ActorId(1), "Player"));
        service.onEvent(new CombatEvent.DamageEvent(
                1_000,
                new ActorId(1),
                "Player",
                new ActorId(100),
                1,
                10_000,
                DamageType.DIRECT,
                false,
                false
        ));
        service.onEvent(new CombatEvent.Tick(1_000));

        assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.partyPace() == null));
        assertTrue(republished.await(2, TimeUnit.SECONDS), "profile refresh should republish snapshot");

        OverlaySnapshot refreshed = snapshots.get(snapshots.size() - 1);
        assertNotNull(refreshed.partyPace());
        assertEquals("FFLogs #1 rDPS: Test", refreshed.partyPace().label());
        assertNull(refreshed.clearability());
    }
}
