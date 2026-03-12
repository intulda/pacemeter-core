package com.bohouse.pacemeter.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaidBuffLibraryTest {

    @Test
    void find_resolvesKnownBuffById() {
        var definition = RaidBuffLibrary.find(new BuffId(0x74F), "ignored").orElseThrow();
        assertEquals(1, definition.effects().size());
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, definition.effects().get(0).kind());
        assertEquals(0.06, definition.effects().get(0).amount(), 0.0001);
    }

    @Test
    void find_resolvesKnownBuffByNameFallback() {
        var definition = RaidBuffLibrary.find(new BuffId(0xFFFF), "The Balance").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, definition.effects().get(0).kind());
        assertEquals(0.06, definition.effects().get(0).amount(), 0.0001);
    }

    @Test
    void find_resolvesCataloguedDirectDamageBuffByName() {
        var definition = RaidBuffLibrary.find(new BuffId(0xEEEE), "Divination").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, definition.effects().get(0).kind());
        assertEquals(0.06, definition.effects().get(0).amount(), 0.0001);
    }

    @Test
    void find_resolvesCritAndDirectHitRateBuffs() {
        var critDefinition = RaidBuffLibrary.find(new BuffId(0xEEEE), "Battle Litany").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.CRIT_RATE, critDefinition.effects().get(0).kind());
        assertEquals(0.10, critDefinition.effects().get(0).amount(), 0.0001);

        var directHitDefinition = RaidBuffLibrary.find(new BuffId(0xEEEE), "Battle Voice").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.DIRECT_HIT_RATE, directHitDefinition.effects().get(0).kind());
        assertEquals(0.20, directHitDefinition.effects().get(0).amount(), 0.0001);
    }

    @Test
    void find_returnsEmptyForUnknownBuff() {
        assertTrue(RaidBuffLibrary.find(new BuffId(0xEEEE), "Unknown Buff").isEmpty());
    }
}
