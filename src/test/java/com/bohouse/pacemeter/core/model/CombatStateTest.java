package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.DamageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStateTest {

    @Test
    void tick_prunesExpiredBuffsWithoutExplicitRemove() {
        CombatState state = new CombatState();
        ActorId source = new ActorId(0x10000001L);
        ActorId target = new ActorId(0x10000002L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, source, "source"));
        state.reduce(new CombatEvent.ActorJoined(0L, target, "target"));
        state.reduce(new CombatEvent.BuffApply(
                1_000L,
                source,
                target,
                new BuffId(1821),
                "정석 마무리",
                15_000L
        ));

        assertEquals(1, state.actors().get(target).activeBuffs().size());

        state.reduce(new CombatEvent.Tick(16_001L));

        assertEquals(0, state.actors().get(target).activeBuffs().size());
    }

    @Test
    void buffReapply_replacesExistingActiveBuffInsteadOfStackingDuplicate() {
        CombatState state = new CombatState();
        ActorId source = new ActorId(0x10000001L);
        ActorId target = new ActorId(0x10000002L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, source, "source"));
        state.reduce(new CombatEvent.ActorJoined(0L, target, "target"));
        state.reduce(new CombatEvent.BuffApply(
                1_000L,
                source,
                target,
                new BuffId(1821),
                "정석 마무리",
                15_000L
        ));
        state.reduce(new CombatEvent.BuffApply(
                5_000L,
                source,
                target,
                new BuffId(1821),
                "정석 마무리",
                60_000L
        ));

        ActorStats targetStats = state.actors().get(target);
        assertNotNull(targetStats);
        assertEquals(1, targetStats.activeBuffs().size());
        assertEquals(5_000L, targetStats.activeBuffs().get(0).appliedAtMs());
        assertEquals(60_000L, targetStats.activeBuffs().get(0).durationMs());
    }

    @Test
    void damageAndDeath_trackingUpdatesHitDeathAndMaxHit() {
        CombatState state = new CombatState();
        ActorId actorId = new ActorId(0x10000001L);
        ActorId targetId = new ActorId(0x40000001L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, actorId, "dealer"));
        state.reduce(new CombatEvent.DamageEvent(
                1_000L, actorId, "dealer", targetId, 0x8C0, 9_000L, DamageType.DIRECT, false, false
        ));
        state.reduce(new CombatEvent.DamageEvent(
                2_000L, actorId, "dealer", targetId, 0x8CF, 12_345L, DamageType.DIRECT, false, false
        ));
        state.reduce(new CombatEvent.ActorDeath(2_500L, actorId, "dealer"));
        state.reduce(new CombatEvent.ActorDeath(2_600L, actorId, "dealer"));

        ActorStats actorStats = state.actors().get(actorId);
        assertNotNull(actorStats);
        assertEquals(2, actorStats.hitCount());
        assertEquals(1, actorStats.deathCount());
        assertEquals(12_345L, actorStats.maxHitDamage());
        assertEquals(0x8CF, actorStats.maxHitActionId());
    }

    @Test
    void maxHit_keepsEventProvidedActionName() {
        CombatState state = new CombatState();
        ActorId actorId = new ActorId(0x10000001L);
        ActorId targetId = new ActorId(0x40000001L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, actorId, "dealer"));
        state.reduce(new CombatEvent.DamageEvent(
                1_000L, actorId, "dealer", targetId, 0x223F, "검은 혜성", 50_000L, DamageType.DIRECT, false, false
        ));

        ActorStats actorStats = state.actors().get(actorId);
        assertNotNull(actorStats);
        assertEquals("검은 혜성", actorStats.maxHitActionName());
    }

    @Test
    void dotDamage_doesNotOverrideMaxHit() {
        CombatState state = new CombatState();
        ActorId actorId = new ActorId(0x10000001L);
        ActorId targetId = new ActorId(0x40000001L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, actorId, "dealer"));
        state.reduce(new CombatEvent.DamageEvent(
                1_000L, actorId, "dealer", targetId, 0x223F, "direct-hit", 8_000L, DamageType.DIRECT, false, false
        ));
        state.reduce(new CombatEvent.DamageEvent(
                2_000L, actorId, "dealer", targetId, 0x40AA, "0x40AA", 9_000L, DamageType.DOT, false, false
        ));

        ActorStats actorStats = state.actors().get(actorId);
        assertNotNull(actorStats);
        assertEquals(8_000L, actorStats.maxHitDamage());
        assertEquals(0x223F, actorStats.maxHitActionId());
        assertEquals("direct-hit", actorStats.maxHitActionName());
    }

    @Test
    void ownerBuffOnOwnedPet_isNotAttributedAsExternalRdps() {
        CombatState state = new CombatState();
        ActorId ownerId = new ActorId(0x10000001L);
        ActorId petId = new ActorId(0x40000010L);
        ActorId targetId = new ActorId(0x40000020L);

        state.reduce(new CombatEvent.FightStart(0L, "test", 1327, 42));
        state.reduce(new CombatEvent.ActorJoined(0L, ownerId, "owner"));
        state.reduce(new CombatEvent.ActorJoined(0L, petId, "pet"));
        state.setOwner(petId, ownerId);
        state.reduce(new CombatEvent.BuffApply(
                1_000L,
                ownerId,
                petId,
                new BuffId(3685),
                "Starry Muse",
                20_000L
        ));
        state.reduce(new CombatEvent.DamageEvent(
                2_000L,
                petId,
                "pet",
                targetId,
                0x8771,
                100_000L,
                DamageType.DIRECT,
                false,
                false
        ));

        ActorStats ownerStats = state.actors().get(ownerId);
        ActorStats petStats = state.actors().get(petId);
        assertNotNull(ownerStats);
        assertNotNull(petStats);
        assertEquals(0.0, ownerStats.totalGrantedBuffContribution(), 0.001);
        assertEquals(0.0, petStats.totalReceivedBuffContribution(), 0.001);
    }

    @Test
    void observeHitOutcome_tracksCritAndDirectHitRatesSeparately() {
        ActorStats stats = new ActorStats(new ActorId(0x10000001L), "dealer");

        stats.observeHitOutcome(false, false);
        stats.observeHitOutcome(true, false);
        stats.observeHitOutcome(false, true);
        stats.observeHitOutcome(true, true);

        assertEquals(4, stats.observedHitSampleCount());
        assertEquals(2, stats.observedCritHitCount());
        assertEquals(2, stats.observedDirectHitCount());
        assertEquals(1, stats.observedCritDirectHitCount());
    }

    @Test
    void experimentalAutoHitAttributionFlag_defaultsOffAndCanBeEnabledByProperty() {
        String previous = System.getProperty(CombatState.EXPERIMENTAL_AUTO_HIT_ATTRIBUTION_PROPERTY);
        try {
            System.clearProperty(CombatState.EXPERIMENTAL_AUTO_HIT_ATTRIBUTION_PROPERTY);
            assertFalse(CombatState.experimentalAutoHitAttributionEnabled());

            System.setProperty(CombatState.EXPERIMENTAL_AUTO_HIT_ATTRIBUTION_PROPERTY, "true");
            assertTrue(CombatState.experimentalAutoHitAttributionEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(CombatState.EXPERIMENTAL_AUTO_HIT_ATTRIBUTION_PROPERTY);
            } else {
                System.setProperty(CombatState.EXPERIMENTAL_AUTO_HIT_ATTRIBUTION_PROPERTY, previous);
            }
        }
    }
}
