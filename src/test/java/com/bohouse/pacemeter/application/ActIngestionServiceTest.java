package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.core.engine.EngineResult;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.DamageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActIngestionServiceTest {

    private final List<CombatEvent> captured = new ArrayList<>();
    private ActIngestionService service;

    @BeforeEach
    void setUp() {
        captured.clear();
        CombatEventPort port = event -> {
            captured.add(event);
            return EngineResult.empty();
        };
        service = new ActIngestionService(port);
    }

    private Instant base() {
        return Instant.parse("2026-02-11T12:00:00Z");
    }

    private void startFight() {
        // PrimaryPlayerChanged → NetworkAbilityRaw(damage>0) 로 전투 시작
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));

        Instant t1 = base().plusMillis(100);
        service.onParsed(new NetworkAbilityRaw(t1, 21, 0x1000000AL, "Warrior",
                0xB4, "Fast Blade", 0x40000001L, "Boss", 5000,
                "21|...|raw"));

        // 전투가 시작되었는지 확인
        assertTrue(service.isFightStarted());
    }

    // ── BuffApply 테스트 ──

    @Test
    void buffApply_beforeFightStarted_ignored() {
        service.onParsed(new BuffApplyRaw(base(), 0x74F, "The Balance", 15.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        // 전투 시작 전이므로 BuffApply 이벤트가 발생하지 않아야 함
        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffApply));
    }

    @Test
    void buffApply_afterFightStarted_emitsCombatEvent() {
        startFight();
        captured.clear();

        Instant buffTime = base().plusMillis(2000);
        service.onParsed(new BuffApplyRaw(buffTime, 0x74F, "The Balance", 15.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertEquals(1, captured.size());
        assertInstanceOf(CombatEvent.BuffApply.class, captured.get(0));

        CombatEvent.BuffApply event = (CombatEvent.BuffApply) captured.get(0);
        assertEquals(0x74F, event.buffId().value());
        assertEquals(new ActorId(0x1000000BL), event.sourceId());
        assertEquals(new ActorId(0x1000000AL), event.targetId());
        assertEquals(15000, event.durationMs()); // 15.0초 → 15000ms
    }

    @Test
    void buffApply_zeroDuration_emitsZeroMs() {
        startFight();
        captured.clear();

        Instant buffTime = base().plusMillis(3000);
        service.onParsed(new BuffApplyRaw(buffTime, 0x1E7, "Shield Oath", 0.0,
                0x1000000AL, "Paladin", 0x1000000AL, "Paladin"));

        assertEquals(1, captured.size());
        CombatEvent.BuffApply event = (CombatEvent.BuffApply) captured.get(0);
        assertEquals(0, event.durationMs());
    }

    // ── BuffRemove 테스트 ──

    @Test
    void buffRemove_beforeFightStarted_ignored() {
        service.onParsed(new BuffRemoveRaw(base(), 0x74F, "The Balance",
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffRemove));
    }

    @Test
    void buffRemove_afterFightStarted_emitsCombatEvent() {
        startFight();
        captured.clear();

        Instant removeTime = base().plusMillis(5000);
        service.onParsed(new BuffRemoveRaw(removeTime, 0x74F, "The Balance",
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertEquals(1, captured.size());
        assertInstanceOf(CombatEvent.BuffRemove.class, captured.get(0));

        CombatEvent.BuffRemove event = (CombatEvent.BuffRemove) captured.get(0);
        assertEquals(0x74F, event.buffId().value());
        assertEquals(new ActorId(0x1000000BL), event.sourceId());
        assertEquals(new ActorId(0x1000000AL), event.targetId());
    }

    // ── 타임스탬프 변환 테스트 ──

    @Test
    void buffApply_timestampMs_isRelativeToFightStart() {
        startFight();
        captured.clear();

        // 전투 시작(base+100ms) 이후 5초 시점에 버프
        Instant buffTime = base().plusMillis(5100);
        service.onParsed(new BuffApplyRaw(buffTime, 0x74F, "The Balance", 10.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        CombatEvent.BuffApply event = (CombatEvent.BuffApply) captured.get(0);
        // fightStartInstant = base+100ms(첫 NetworkAbilityRaw 시점), buffTime = base+5100ms
        // elapsed = 5000ms
        assertEquals(5000, event.timestampMs(), 100); // 약간의 오차 허용
    }
}
