package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoHitCatalogTest {

    @Test
    void resolvesWarriorInnerReleaseActionsAsGuaranteedCritDirectHit() {
        CombatEvent.HitOutcomeContext context = AutoHitCatalog.resolve(
                0x15,
                0x0DDD,
                "Fell Cleave",
                Set.of(),
                Set.of(),
                Set.of("inner release")
        ).orElseThrow();

        assertEquals(CombatEvent.AutoHitFlag.YES, context.autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.YES, context.autoDirectHit());
    }

    @Test
    void resolvesDragoonLifeSurgeWeaponskillAsGuaranteedCritOnly() {
        CombatEvent.HitOutcomeContext context = AutoHitCatalog.resolve(
                0x16,
                0x9058,
                "Drakesbane",
                Set.of(ActionTagCatalog.ActionTag.WEAPONSKILL),
                Set.of(),
                Set.of("life surge")
        ).orElseThrow();

        assertEquals(CombatEvent.AutoHitFlag.YES, context.autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.NO, context.autoDirectHit());
    }

    @Test
    void resolvesMachinistReassembleWeaponskillAsGuaranteedCritDirectHit() {
        CombatEvent.HitOutcomeContext context = AutoHitCatalog.resolve(
                0x1F,
                0x4072,
                "Drill",
                Set.of(ActionTagCatalog.ActionTag.WEAPONSKILL),
                Set.of(),
                Set.of("reassemble")
        ).orElseThrow();

        assertEquals(CombatEvent.AutoHitFlag.YES, context.autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.YES, context.autoDirectHit());
    }

    @Test
    void returnsEmptyForSamuraiActionOnlyRuleUntilParitySafeEvidenceExists() {
        assertTrue(AutoHitCatalog.resolve(
                0x22,
                0x64B5,
                "Ogi Namikiri",
                Set.of(),
                Set.of(),
                Set.of()
        ).isEmpty());
    }

    @Test
    void returnsEmptyWhenRequiredSelfBuffIsMissing() {
        assertTrue(AutoHitCatalog.resolve(
                0x1F,
                0x4072,
                "Drill",
                Set.of(ActionTagCatalog.ActionTag.WEAPONSKILL),
                Set.of(),
                Set.of()
        ).isEmpty());
    }

    @Test
    void returnsEmptyWhenRequiredActionTagIsMissing() {
        assertTrue(AutoHitCatalog.resolve(
                0x1F,
                0x4072,
                "Drill",
                Set.of(),
                Set.of(),
                Set.of("reassemble")
        ).isEmpty());
    }
}
