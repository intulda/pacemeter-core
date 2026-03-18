package com.bohouse.pacemeter.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaidBuffLibraryTest {

    @Test
    void find_resolvesKnownBuffById() {
        var definition = RaidBuffLibrary.find(new BuffId(0xF2F), "ignored").orElseThrow();
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
    void find_resolvesHeavy3BuffsByReplayIdsAndKoreanNames() {
        var divination = RaidBuffLibrary.find(new BuffId(0x756), "점복").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, divination.effects().get(0).kind());
        assertEquals(0.06, divination.effects().get(0).amount(), 0.0001);

        var technicalFinish = RaidBuffLibrary.find(new BuffId(0x71E), "기교 마무리").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, technicalFinish.effects().get(0).kind());
        assertEquals(0.05, technicalFinish.effects().get(0).amount(), 0.0001);

        var standardFinish = RaidBuffLibrary.find(new BuffId(0x71D), "정석 마무리").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, standardFinish.effects().get(0).kind());
        assertEquals(0.05, standardFinish.effects().get(0).amount(), 0.0001);

        var balance = RaidBuffLibrary.find(new BuffId(0xF2F), "아제마의 균형").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.PERCENT_DAMAGE, balance.effects().get(0).kind());
        assertEquals(0.06, balance.effects().get(0).amount(), 0.0001);
    }

    @Test
    void catalog_doesNotContainKnownDefensiveBuffAsRaidDamageBuff() {
        assertTrue(RaidBuffLibrary.definitions().stream().noneMatch(definition ->
                definition.ids().contains(new BuffId(0x766))
                        || definition.aliases().contains("어둠의 포교자")));
    }

    @Test
    void find_resolvesCritAndDirectHitRateBuffs() {
        var critDefinition = RaidBuffLibrary.find(new BuffId(0x312), "ignored").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.CRIT_RATE, critDefinition.effects().get(0).kind());
        assertEquals(0.10, critDefinition.effects().get(0).amount(), 0.0001);

        var directHitDefinition = RaidBuffLibrary.find(new BuffId(0x721), "ignored").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.CRIT_RATE, directHitDefinition.effects().get(0).kind());
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.DIRECT_HIT_RATE, directHitDefinition.effects().get(1).kind());
        assertEquals(0.20, directHitDefinition.effects().get(0).amount(), 0.0001);
        assertEquals(0.20, directHitDefinition.effects().get(1).amount(), 0.0001);

        var battleVoice = RaidBuffLibrary.find(new BuffId(0xEEEE), "Battle Voice").orElseThrow();
        assertEquals(RaidBuffLibrary.RaidBuffEffect.Kind.DIRECT_HIT_RATE, battleVoice.effects().get(0).kind());
        assertEquals(0.20, battleVoice.effects().get(0).amount(), 0.0001);
    }

    @Test
    void find_returnsEmptyForUnknownBuff() {
        assertTrue(RaidBuffLibrary.find(new BuffId(0xEEEE), "Unknown Buff").isEmpty());
    }
}
