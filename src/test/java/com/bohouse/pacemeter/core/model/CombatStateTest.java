package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
