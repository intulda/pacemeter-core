package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.event.CombatEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayEventParserTest {

    @Test
    void damageEvent_withoutAutoHitFields_defaultsToUnknown() throws Exception {
        String jsonl = """
                {"type":"DamageEvent","timestampMs":1000,"sourceId":1,"sourceName":"Player","targetId":100,"actionId":7,"amount":15000,"damageType":"DIRECT","criticalHit":true,"directHit":false}
                """;

        List<CombatEvent> events = ReplayEventParser.parse(
                new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8))
        );

        CombatEvent.DamageEvent event = (CombatEvent.DamageEvent) events.get(0);
        assertEquals(CombatEvent.AutoHitFlag.UNKNOWN, event.hitOutcomeContext().autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.UNKNOWN, event.hitOutcomeContext().autoDirectHit());
    }

    @Test
    void damageEvent_withAutoHitFields_readsExplicitFlags() throws Exception {
        String jsonl = """
                {"type":"DamageEvent","timestampMs":1000,"sourceId":1,"sourceName":"Player","targetId":100,"actionId":7,"amount":15000,"damageType":"DIRECT","criticalHit":true,"directHit":true,"autoCrit":"YES","autoDirectHit":"NO"}
                """;

        List<CombatEvent> events = ReplayEventParser.parse(
                new ByteArrayInputStream(jsonl.getBytes(StandardCharsets.UTF_8))
        );

        CombatEvent.DamageEvent event = (CombatEvent.DamageEvent) events.get(0);
        assertEquals(CombatEvent.AutoHitFlag.YES, event.hitOutcomeContext().autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.NO, event.hitOutcomeContext().autoDirectHit());
    }
}
