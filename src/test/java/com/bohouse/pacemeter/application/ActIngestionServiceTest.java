package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.*;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.engine.EngineResult;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.DamageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ActIngestionServiceTest {

    private final List<CombatEvent> captured = new ArrayList<>();
    private ActIngestionService service;

    @BeforeEach
    void setUp() {
        captured.clear();
        CombatEventPort port = new CombatEventPort() {
            @Override
            public EngineResult onEvent(CombatEvent event) {
                captured.add(event);
                return EngineResult.empty();
            }

            @Override
            public void setCurrentPlayerId(ActorId playerId) {
                // Mock: do nothing
            }

            @Override
            public void setJobId(ActorId actorId, int jobId) {
                // Mock: do nothing
            }
        };
        // Mock CombatService (jobId 설정은 무시)
        CombatService mockCombatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        service = new ActIngestionService(port, mockCombatService, new FflogsZoneLookup(new ObjectMapper()));
    }

    private Instant base() {
        return Instant.parse("2026-02-11T12:00:00Z");
    }

    private void initializeZoneAndParty() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
    }

    private void startFight() {
        initializeZoneAndParty();

        Instant t1 = base().plusMillis(100);
        service.onParsed(new NetworkAbilityRaw(t1, 21, 0x1000000AL, "Warrior",
                0xB4, "Fast Blade", 0x40000001L, "나무인형", false, false, 5000,
                "21|...|raw"));

        // 전투가 시작되었는지 확인
        assertTrue(service.isFightStarted());
    }

    // ── BuffApply 테스트 ──

    @Test
    void buffApply_beforeFightStarted_emitsWhenFightStarts() {
        initializeZoneAndParty();
        service.onParsed(new BuffApplyRaw(base(), 0x74F, "The Balance", 15.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffApply));

        Instant t1 = base().plusMillis(100);
        service.onParsed(new NetworkAbilityRaw(t1, 21, 0x1000000AL, "Warrior",
                0xB4, "Fast Blade", 0x40000001L, "나무인형", false, false, 5000,
                "21|...|raw"));

        CombatEvent.BuffApply event = captured.stream()
                .filter(CombatEvent.BuffApply.class::isInstance)
                .map(CombatEvent.BuffApply.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(new ActorId(0x1000000BL), event.sourceId());
        assertEquals(new ActorId(0x1000000AL), event.targetId());
        assertEquals(0L, event.timestampMs());
    }

    @Test
    void buffRemove_beforeFightStarted_emitsWhenFightStarts() {
        initializeZoneAndParty();
        service.onParsed(new BuffRemoveRaw(base(), 0x74F, "The Balance",
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffApply));
        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffRemove));

        Instant t1 = base().plusMillis(100);
        service.onParsed(new NetworkAbilityRaw(t1, 21, 0x1000000AL, "Warrior",
                0xB4, "Fast Blade", 0x40000001L, "나무인형", false, false, 5000,
                "21|...|raw"));

        CombatEvent.BuffRemove event = captured.stream()
                .filter(CombatEvent.BuffRemove.class::isInstance)
                .map(CombatEvent.BuffRemove.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(new ActorId(0x1000000BL), event.sourceId());
        assertEquals(new ActorId(0x1000000AL), event.targetId());
        assertEquals(0L, event.timestampMs());
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

    @Test
    void damageText_isMatchedToNetworkAbilityForCritAndDirectFlags() {
        startFight();
        captured.clear();

        Instant damageTime = base().plusMillis(1500);
        service.onParsed(new DamageText(
                damageTime,
                "Warrior",
                "나무인형",
                5000,
                true,
                true,
                "00|...|12A9",
                "극대화 직격!"
        ));
        service.onParsed(new NetworkAbilityRaw(
                damageTime.plusMillis(50),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "나무인형",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertTrue(event.criticalHit());
        assertTrue(event.directHit());
    }

    @Test
    void damageText_withDifferentSourceName_stillMatchesByAmountTargetAndTime() {
        startFight();
        captured.clear();

        Instant damageTime = base().plusMillis(1500);
        service.onParsed(new DamageText(
                damageTime,
                "다른이름",
                "나무인형",
                5000,
                true,
                false,
                "00|...|12A9",
                "극대화!"
        ));
        service.onParsed(new NetworkAbilityRaw(
                damageTime.plusMillis(50),
                0x0A9F,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "나무인형",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertTrue(event.criticalHit());
        assertFalse(event.directHit());
    }

    @Test
    void dotTick_emitsDotDamageEvent() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101C2E9EL)));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "나무인형",
                "DoT",
                0x2ED,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(DamageType.DOT, event.damageType());
        assertEquals(new ActorId(0x101C2E9EL), event.sourceId());
        assertEquals(new ActorId(0x40000001L), event.targetId());
        assertEquals(0x2ED, event.actionId());
        assertEquals(0xEBACL, event.amount());
        assertFalse(event.criticalHit());
        assertFalse(event.directHit());
    }

    @Test
    void hotTick_isIgnored() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101C2E9EL)));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x1000000AL,
                "Warrior",
                "HoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_isIgnored() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "나무인형",
                "DoT",
                0,
                0x1000000AL,
                "Warrior",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_forTrackedJob_requiresRecentApplication() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101C2E9EL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x101C2E9EL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x101C2E9EL,
                "Scholar",
                0x409C,
                "Biolysis",
                0x40000001L,
                "보스",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(0x409C, event.actionId());
    }

    @Test
    void dotTick_withUnknownStatusId_forNinja_requiresRecentDokumoriApplication() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1013ABCDL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1013ABCDL,
                "Ninja",
                0x1E,
                0,
                100_000L,
                100_000L,
                "03|...|Ninja"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x1013ABCDL,
                "Ninja",
                0xEBA0L,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x1013ABCDL,
                "Ninja",
                0x1093,
                "Dokumori",
                0x40000001L,
                "보스",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x1013ABCDL,
                "Ninja",
                0xEBA0L,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(0x1093, event.actionId());
    }

    @Test
    void dotTick_withUnknownStatusId_forSage_requiresRecentEukrasianDosisApplication() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x10127ABCL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x10127ABCL,
                "Sage",
                0x28,
                0,
                100_000L,
                100_000L,
                "03|...|Sage"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x10127ABCL,
                "Sage",
                0x5EF8,
                "Eukrasian Dosis III",
                0x40000001L,
                "보스",
                false,
                false,
                1200,
                "21|...|raw"
        ));

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x10127ABCL,
                "Sage",
                0xEBA0L,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(0x5EF8, event.actionId());
    }

    @Test
    void dotTick_withUnknownStatusId_forTrackedJob_isIgnoredWhenApplicationExpired() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101C2E9EL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x101C2E9EL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x101C2E9EL,
                "Scholar",
                0x409C,
                "Biolysis",
                0x40000001L,
                "보스",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusSeconds(46),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_forTrackedJob_isIgnoredWhenApplicationWasOnDifferentTarget() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101C2E9EL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x101C2E9EL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x101C2E9EL,
                "Scholar",
                0x409C,
                "Biolysis",
                0x40000002L,
                "다른 보스",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_forDragoon_requiresRecentChaoticSpringStatusApply() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x101589A6L)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x101589A6L,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101589A6L,
                "Dragoon",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));

        service.onParsed(new BuffApplyRaw(
                base().plusMillis(300),
                0x0A9F,
                "Chaotic Spring",
                24.0,
                0x101589A6L,
                "Dragoon",
                0x40000001L,
                "보스"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x101589A6L,
                "Dragoon",
                0xEBACL,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(DamageType.DOT, event.damageType());
        assertEquals(new ActorId(0x101589A6L), event.sourceId());
        assertEquals(0x0A9F, event.actionId());
    }

    @Test
    void dotTick_withUnknownStatusId_forFallbackJob_stillUsesWhitelist() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x10180001L)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x10180001L,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "보스",
                "DoT",
                0,
                0x10180001L,
                "WhiteMage",
                0xEBACL,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(DamageType.DOT, event.damageType());
        assertEquals(new ActorId(0x10180001L), event.sourceId());
        assertEquals(0, event.actionId());
    }

    @Test
    void networkAbility_targetingFriendlyActor_isIgnored() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL)));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x1000000BL,
                "Scholar",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void networkAbility_from261OwnedSummon_isAttributedToParty() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x400066A3L,
                "Summon",
                0,
                0x1000000AL,
                0L,
                215512L,
                "261|...|Add"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x400066A3L,
                "Summon",
                0x875A,
                "Summon Attack",
                0x40000001L,
                "나무인형",
                false,
                false,
                40000,
                "21|...|raw"
        ));

        assertTrue(captured.stream().anyMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void combatantAdded_bossDuringFight_emitsBossIdentified() {
        startFight();
        captured.clear();

        service.onParsed(new CombatantAdded(
                base().plusMillis(300),
                0x40000001L,
                "Boss",
                0,
                0,
                120_000_000L,
                120_000_000L,
                "03|..."
        ));

        assertEquals(1, captured.size());
        assertInstanceOf(CombatEvent.BossIdentified.class, captured.get(0));

        CombatEvent.BossIdentified event = (CombatEvent.BossIdentified) captured.get(0);
        assertEquals(new ActorId(0x40000001L), event.actorId());
        assertEquals("Boss", event.actorName());
        assertEquals(120_000_000L, event.maxHp());
    }

    @Test
    void combatantAdded_bossBeforeFightStart_emitsAfterFightStart() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));

        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x40000001L,
                "Boss",
                0,
                0,
                120_000_000L,
                120_000_000L,
                "03|..."
        ));

        assertTrue(captured.isEmpty());

        service.onParsed(new PrimaryPlayerChanged(base().plusMillis(100), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base().plusMillis(100), List.of(0x1000000AL)));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "나무인형",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        assertTrue(captured.stream().anyMatch(e -> e instanceof CombatEvent.BossIdentified));
    }

    @Test
    void networkFlags_areUsedWithoutDamageTextMatch() {
        startFight();
        captured.clear();

        Instant damageTime = base().plusMillis(1500);
        service.onParsed(new NetworkAbilityRaw(
                damageTime,
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "나무인형",
                true,
                true,
                5000,
                "21|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertTrue(event.criticalHit());
        assertTrue(event.directHit());
    }

    @Test
    void combatantAdded_playerCharacter_isNotBoss() {
        startFight();
        captured.clear();

        service.onParsed(new CombatantAdded(
                base().plusMillis(300),
                0x1000000AL,
                "Warrior",
                0x15,
                0,
                80_000_000L,
                80_000_000L,
                "03|..."
        ));

        assertTrue(captured.isEmpty());
    }

    @Test
    void fightEnd_preservesCombatantContextForNextPullInSameZone() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1013CC4BL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1013CC4BL,
                "나성",
                0x28,
                0,
                191512L,
                191512L,
                "03|..."
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1013CC4BL,
                "나성",
                0x5EF8,
                "Player127",
                0x4000664CL,
                "보스",
                false,
                false,
                1000,
                "21|..."
        ));
        service.onParsed(new NetworkDeath(
                base().plusMillis(200),
                0x1013CC4BL,
                "나성"
        ));

        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusSeconds(40),
                21,
                0x1013CC4BL,
                "나성",
                0x5EF8,
                "Player127",
                0x4000664CL,
                "보스",
                false,
                false,
                1000,
                "21|..."
        ));
        service.onParsed(new DotTickRaw(
                base().plusSeconds(41),
                0x4000664CL,
                "보스",
                "DoT",
                0,
                0x1013CC4BL,
                "나성",
                2500,
                "24|..."
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .filter(damage -> damage.damageType() == DamageType.DOT)
                .findFirst()
                .orElseThrow();

        assertEquals(0x5EF8, event.actionId());
        assertEquals(new ActorId(0x1013CC4BL), event.sourceId());
    }
}
