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
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

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
        // Mock CombatService. jobId 전달 여부만 검증하면 된다.
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

    private void clearSelfJobCache(long playerId, String playerName) {
        try {
            Preferences node = Preferences.userNodeForPackage(ActIngestionService.class).node("live-self-job");
            node.remove("player-id:" + Long.toHexString(playerId));
            node.remove("player-name:" + playerName);
            node.flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException(e);
        }
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
                0xB4, "Fast Blade", 0x40000001L, "Training Dummy", false, false, 5000,
                "21|...|raw"));

        // 전투가 시작됐는지 확인
        assertTrue(service.isFightStarted());
    }

    // BuffApply 테스트

    @Test
    void buffApply_beforeFightStarted_emitsWhenFightStarts() {
        initializeZoneAndParty();
        service.onParsed(new BuffApplyRaw(base(), 0x74F, "The Balance", 15.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        assertTrue(captured.stream().noneMatch(e -> e instanceof CombatEvent.BuffApply));

        Instant t1 = base().plusMillis(100);
        service.onParsed(new NetworkAbilityRaw(t1, 21, 0x1000000AL, "Warrior",
                0xB4, "Fast Blade", 0x40000001L, "Training Dummy", false, false, 5000,
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
                0xB4, "Fast Blade", 0x40000001L, "Training Dummy", false, false, 5000,
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
        assertEquals(15000, event.durationMs()); // 15.0초 = 15000ms
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

    @Test
    void dotTick_keepsEmittingForExistingMember_whenPartyListShrinksMidFight() {
        service.onParsed(new ZoneChanged(base(), 1, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL)));

        // 전투 시작
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Training Dummy",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        assertTrue(service.isFightStarted());
        captured.clear();

        // 전투 중 PartyList가 축소되는 시나리오
        service.onParsed(new PartyList(base().plusMillis(200), List.of(0x1000000AL)));

        service.onParsed(new DotTickRaw(
                base().plusMillis(300),
                0x40000001L,
                "Boss",
                "DoT",
                0x248,
                0x1000000BL,
                "Scholar",
                12345,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent dotEvent = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .filter(event -> event.sourceId().equals(new ActorId(0x1000000BL)))
                .findFirst()
                .orElseThrow();

        assertEquals(DamageType.DOT, dotEvent.damageType());
        assertEquals(12345, dotEvent.amount());
    }

    @Test
    void dotTick_withUnknownSource_usesSingleRecentApplicationCandidate() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long whiteMageId = 0x10180001L;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, whiteMageId)));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageId,
                "WhiteMage",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        // 전투 시작
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        // Dia application
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                whiteMageId,
                "WhiteMage",
                0x4094,
                "Dia",
                bossId,
                "Boss",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        // sourceId가 비어 있는 DoT tick
        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                bossId,
                "Boss",
                "DoT",
                0,
                0xE0000000L,
                "",
                0xEBACL,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(new ActorId(whiteMageId), event.sourceId());
        assertEquals(0x4094, event.actionId());
        assertEquals(DamageType.DOT, event.damageType());
    }

    @Test
    void dotTick_withKnownStatusAndUnknownSource_usesRecentStatusEvidence() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long whiteMageId = 0x10180001L;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, whiteMageId)));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageId,
                "WhiteMage",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new BuffApplyRaw(
                base().plusMillis(200),
                0x74F,
                "Dia",
                30.0,
                whiteMageId,
                "WhiteMage",
                bossId,
                "Boss"
        ));

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                bossId,
                "Boss",
                "DoT",
                0x74F,
                0xE0000000L,
                "",
                0xEA60L,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(new ActorId(whiteMageId), event.sourceId());
        assertEquals(0x4094, event.actionId());
        assertEquals(DamageType.DOT, event.damageType());
        assertEquals(60000L, event.amount());
    }

    @Test
    void dotTick_withKnownStatusAndUnknownSource_usesUniqueJobFallbackWithoutRecentEvidence() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long whiteMageId = 0x10180001L;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, whiteMageId)));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageId,
                "WhiteMage",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        // sourceId는 unknown이고 status는 known(Dia)이며 recent application evidence는 없다.
        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                bossId,
                "Boss",
                "DoT",
                0x74F,
                0xE0000000L,
                "",
                0xEA60L,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(new ActorId(whiteMageId), event.sourceId());
        assertEquals(0x4094, event.actionId());
        assertEquals(60000L, event.amount());
        assertEquals(DamageType.DOT, event.damageType());
    }

    @Test
    void dotTick_withKnownStatusAndUnknownSource_doesNotFallbackWhenMatchingJobIsAmbiguous() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long whiteMageA = 0x10180001L;
        long whiteMageB = 0x10180002L;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, whiteMageA, whiteMageB)));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageA,
                "WhiteMageA",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageB,
                "WhiteMageB",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                bossId,
                "Boss",
                "DoT",
                0x74F,
                0xE0000000L,
                "",
                0xEA60L,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withAutoAttackLikeStatusId_dropsInvalidDotAction() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long sourceId = 0x1000000AL;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(sourceId)));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                sourceId,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        // resolveDotActionId == 0x17 이면 유효한 DoT 액션으로 보지 않는다.
        service.onParsed(new DotTickRaw(
                base().plusMillis(200),
                bossId,
                "Boss",
                "DoT",
                0x17,
                sourceId,
                "Warrior",
                12345,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(event ->
                event instanceof CombatEvent.DamageEvent damageEvent
                        && damageEvent.damageType() == DamageType.DOT
        ));
    }

    // BuffRemove 테스트

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

    // 타임스탬프 관련 테스트

    @Test
    void buffApply_timestampMs_isRelativeToFightStart() {
        startFight();
        captured.clear();

        // 전투 시작(base+100ms) 이후 5초 시점의 버프
        Instant buffTime = base().plusMillis(5100);
        service.onParsed(new BuffApplyRaw(buffTime, 0x74F, "The Balance", 10.0,
                0x1000000BL, "Astrologian", 0x1000000AL, "Warrior"));

        CombatEvent.BuffApply event = (CombatEvent.BuffApply) captured.get(0);
        // fightStartInstant = base+100ms, buffTime = base+5100ms
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
                "Training Dummy",
                5000,
                true,
                true,
                "00|...|12A9",
                "洹밸???吏곴꺽!"
        ));
        service.onParsed(new NetworkAbilityRaw(
                damageTime.plusMillis(50),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Training Dummy",
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
                "?ㅻⅨ?대쫫",
                "Training Dummy",
                5000,
                true,
                false,
                "00|...|12A9",
                "洹밸???"
        ));
        service.onParsed(new NetworkAbilityRaw(
                damageTime.plusMillis(50),
                0x0A9F,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Training Dummy",
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
    void guaranteedAutoHitRule_setsAutoCritAndAutoDirectHitWhenRequiredSelfBuffIsActive() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base(),
                0x1000000AL,
                "Warrior",
                0x15,
                0L,
                250000,
                250000,
                "03|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(50),
                21,
                0x1000000AL,
                "Warrior",
                0x00B4,
                "Fast Blade",
                0x40000001L,
                "Training Dummy",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(80),
                0xDEAD,
                "Inner Release",
                15.0,
                0x1000000AL,
                "Warrior",
                0x1000000AL,
                "Warrior"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(1000),
                21,
                0x1000000AL,
                "Warrior",
                0x0DDD,
                "Fell Cleave",
                0x40000001L,
                "Training Dummy",
                true,
                true,
                20000,
                "21|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertEquals(CombatEvent.AutoHitFlag.YES, event.hitOutcomeContext().autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.YES, event.hitOutcomeContext().autoDirectHit());
    }

    @Test
    void guaranteedAutoHitRule_withoutRequiredSelfBuff_keepsOutcomeUnknown() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base(),
                0x1000000AL,
                "Warrior",
                0x15,
                0L,
                250000,
                250000,
                "03|...|raw"
        ));
        captured.clear();
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(50),
                21,
                0x1000000AL,
                "Warrior",
                0x00B4,
                "Fast Blade",
                0x40000001L,
                "Training Dummy",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(1000),
                21,
                0x1000000AL,
                "Warrior",
                0x0DDD,
                "Fell Cleave",
                0x40000001L,
                "Training Dummy",
                true,
                true,
                20000,
                "21|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertEquals(CombatEvent.AutoHitFlag.UNKNOWN, event.hitOutcomeContext().autoCrit());
        assertEquals(CombatEvent.AutoHitFlag.UNKNOWN, event.hitOutcomeContext().autoDirectHit());
    }

    @Test
    void unknownStatusDot_withoutSameTargetEvidence_rejectsActionFallback() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long scholarId = 0x1000000BL;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, scholarId)));
        service.onParsed(new CombatantAdded(
                base(),
                scholarId,
                "Scholar",
                0x1C,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "蹂댁뒪",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        Instant t = base().plusMillis(2_000);
        service.onParsed(new NetworkAbilityRaw(
                t,
                21,
                scholarId,
                "Scholar",
                0x409C,
                "Baneful Impaction",
                0x40000011L,
                "蹂댁뒪A",
                false,
                false,
                10_000,
                "21|...|raw"
        ));
        captured.clear();

        DotTickRaw dot = new DotTickRaw(
                t.plusMillis(1_500),
                0x40000022L,
                "蹂댁뒪B",
                "DoT",
                0,
                scholarId,
                "Scholar",
                12_345,
                "24|...|raw"
        );

        assertFalse(service.wouldEmitDotDamage(dot));
        assertEquals(0, service.resolveDotActionId(dot));

        service.onParsed(dot);
        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void unknownStatusDot_withoutSameTargetEvidence_rejectsStatusFallback() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long scholarId = 0x1000000BL;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, scholarId)));
        service.onParsed(new CombatantAdded(
                base(),
                scholarId,
                "Scholar",
                0x1C,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "蹂댁뒪",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        Instant t = base().plusMillis(2_000);
        service.onParsed(new BuffApplyRaw(
                t,
                0x0767,
                "Biolysis",
                30.0,
                scholarId,
                "Scholar",
                0x40000011L,
                "蹂댁뒪A"
        ));

        DotTickRaw dot = new DotTickRaw(
                t.plusMillis(1_500),
                0x40000022L,
                "蹂댁뒪B",
                "DoT",
                0,
                scholarId,
                "Scholar",
                11_111,
                "24|...|raw"
        );

        assertFalse(service.wouldEmitDotDamage(dot));
        assertEquals(0, service.resolveDotActionId(dot));
    }

    @Test
    void unknownStatusDot_withoutTrackingSignals_isRejectedForUnconfiguredJob() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long actorId = 0x1000000BL;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, actorId)));
        service.onParsed(new CombatantAdded(
                base(),
                actorId,
                "UnconfiguredJob",
                0x25,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(1500),
                bossId,
                "Boss",
                "DoT",
                0,
                actorId,
                "UnconfiguredJob",
                12345,
                "24|...|raw"
        );

        assertFalse(service.wouldEmitDotDamage(dot));
        service.onParsed(dot);
        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void unknownStatusDot_withType37Signal_acceptsAndMapsToTrackedAction() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long scholarId = 0x1000000BL;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, scholarId)));
        service.onParsed(new CombatantAdded(
                base(),
                scholarId,
                "Scholar",
                0x1C,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Warrior",
                0xB4,
                "Fast Blade",
                bossId,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotStatusSignalRaw(
                base().plusMillis(500),
                bossId,
                List.of(new DotStatusSignalRaw.StatusSignal(0x0767, scholarId)),
                "37|...|raw"
        ));

        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(2_000),
                bossId,
                "Boss",
                "DoT",
                0,
                scholarId,
                "Scholar",
                12_345,
                "24|...|raw"
        );

        assertTrue(service.wouldEmitDotDamage(dot));
        assertEquals(0x409C, service.resolveDotActionId(dot));
    }

    @Test
    void unknownStatusDot_prefersCorroboratedMarkerAndType37SignalOverNewerMismatchedMarker() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        long scholarId = 0x1000000BL;
        long whiteMageId = 0x1000000CL;
        long bossId = 0x40000011L;
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, scholarId, whiteMageId)));
        service.onParsed(new CombatantAdded(
                base(),
                scholarId,
                "Scholar",
                0x1C,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));
        service.onParsed(new CombatantAdded(
                base(),
                whiteMageId,
                "WhiteMage",
                0x18,
                0L,
                190000,
                190000,
                "03|...|raw"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                scholarId,
                "Scholar",
                0x409C,
                "Biolysis",
                bossId,
                "Boss",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        service.onParsed(new DotStatusSignalRaw(
                base().plusMillis(200),
                bossId,
                List.of(new DotStatusSignalRaw.StatusSignal(0x0767, scholarId)),
                "37|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(900),
                21,
                whiteMageId,
                "WhiteMage",
                0x4094,
                "Dia",
                bossId,
                "Boss",
                false,
                false,
                900,
                "21|...|raw"
        ));

        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(2_000),
                bossId,
                "Boss",
                "DoT",
                0,
                scholarId,
                "Scholar",
                12_345,
                "24|...|raw"
        );

        assertEquals(0x409C, service.resolveDotActionId(dot));
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
                "Training Dummy",
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
    void dotTick_withKnownTrackedStatus_emitsMappedActionId() {
        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(200),
                0x40000001L,
                "Boss",
                "DoT",
                0x74F,
                0x102884E5L,
                "WhiteMage",
                0xEBACL,
                "24|...|raw"
        );

        assertEquals(0x4094, service.resolveDotActionId(dot));
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
                "Training Dummy",
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
                "蹂댁뒪",
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
                "蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
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
    void dotTick_withUnknownStatusId_onEnemyTarget_prefersTrackedSourceDot() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Sage"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Sage",
                0x28,
                0,
                100_000L,
                100_000L,
                "03|...|Sage"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Sage",
                0x5EF8,
                "Dosis III",
                0x40000001L,
                "Boss",
                false,
                false,
                5_000,
                "21|...|raw"
        ));
        captured.clear();

        Instant applyTime = base().plusMillis(1_000);
        service.onParsed(new BuffApplyRaw(
                applyTime,
                0x0A38,
                "Eukrasian Dosis III",
                30.0,
                0x1000000AL,
                "Sage",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                applyTime.plusMillis(100),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000001L,
                "Boss"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                applyTime.plusMillis(3_000),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Sage",
                9_001,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(2, events.size());
        assertEquals(9_001L, events.stream().mapToLong(CombatEvent.DamageEvent::amount).sum());
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000AL)) && event.actionId() == 0x5EFA));
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000BL)) && event.actionId() == 0x409C));
    }

    @Test
    void dotTick_withUnknownStatusId_fromIgnoredSource_redistributesUsingRecentSnapshot() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Sage"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Sage",
                0x28,
                0,
                100_000L,
                100_000L,
                "03|...|Sage"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(80),
                0x1000000DL,
                "Paladin",
                0x13,
                0,
                100_000L,
                100_000L,
                "03|...|Paladin"
        ));
        captured.clear();

        Instant snapshotTime = base().plusMillis(1_000);
        service.onParsed(new StatusSnapshotRaw(
                snapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x0A38, "42200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41F00000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x0F2B, "41200000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x0A9F, "41A00000", 0x1000000CL)
                ),
                "38|...|raw"
        ));

        service.onParsed(new DotTickRaw(
                snapshotTime.plusMillis(100),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000DL,
                "Paladin",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(3, events.size());
        assertEquals(7_835L, events.stream().mapToLong(CombatEvent.DamageEvent::amount).sum());
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000AL)) && event.actionId() == 0x5EFA));
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000BL)) && event.actionId() == 0x409C));
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000BL)) && event.actionId() == 0x9094));
        assertTrue(events.stream().noneMatch(event -> event.sourceId().equals(new ActorId(0x1000000CL)) && event.actionId() == 0x64AC));
    }

    @Test
    void dotTick_withUnknownStatusId_usesActiveTrackedDotsSubsetBeforeFullSnapshotFallback() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Sage"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Sage",
                0x28,
                0,
                100_000L,
                100_000L,
                "03|...|Sage"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(80),
                0x1000000DL,
                "Paladin",
                0x13,
                0,
                100_000L,
                100_000L,
                "03|...|Paladin"
        ));

        Instant applyTime = base().plusMillis(900);
        service.onParsed(new BuffApplyRaw(
                applyTime,
                0x0A38,
                "Eukrasian Dosis III",
                30.0,
                0x1000000AL,
                "Sage",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                applyTime.plusMillis(50),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000001L,
                "Boss"
        ));
        captured.clear();

        Instant snapshotTime = base().plusMillis(1_000);
        service.onParsed(new StatusSnapshotRaw(
                snapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x0A38, "42200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41F00000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x0A9F, "41A00000", 0x1000000CL)
                ),
                "38|...|raw"
        ));

        service.onParsed(new DotTickRaw(
                snapshotTime.plusMillis(100),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000DL,
                "Paladin",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(2, events.size());
        assertEquals(10_000L, events.stream().mapToLong(CombatEvent.DamageEvent::amount).sum());
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000AL)) && event.actionId() == 0x5EFA));
        assertTrue(events.stream().anyMatch(event -> event.sourceId().equals(new ActorId(0x1000000BL)) && event.actionId() == 0x409C));
        assertTrue(events.stream().noneMatch(event -> event.sourceId().equals(new ActorId(0x1000000CL)) && event.actionId() == 0x64AC));
    }

    @Test
    void dotTick_withUnknownStatusId_singleActiveTrackedDot_prefersKnownSourceAttribution() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Samurai"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Samurai",
                0x22,
                0,
                100_000L,
                100_000L,
                "03|...|Samurai"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        Instant applyTime = base().plusMillis(900);
        service.onParsed(new BuffApplyRaw(
                applyTime,
                0x04CC,
                "Higanbana",
                60.0,
                0x1000000AL,
                "Samurai",
                0x40000001L,
                "Boss"
        ));
        captured.clear();

        Instant snapshotTime = base().plusMillis(1_000);
        service.onParsed(new StatusSnapshotRaw(
                snapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x04CC, "42200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41900000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));

        service.onParsed(new DotTickRaw(
                snapshotTime.plusMillis(100),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Samurai",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertTrue(events.isEmpty());
    }

    @Test
    void dotTick_withUnknownStatusId_knownPartySource_skipsSnapshotRedistribution() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Samurai"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Samurai",
                0x22,
                0,
                100_000L,
                100_000L,
                "03|...|Samurai"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(900),
                0x04CC,
                "Higanbana",
                60.0,
                0x1000000AL,
                "Samurai",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new StatusSnapshotRaw(
                base().plusMillis(1_000),
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x04CC, "42200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41900000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(1_100),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Samurai",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertTrue(events.isEmpty());

        LiveDotAttributionDebugSnapshot debugSnapshot = service.debugLiveDotAttributionSnapshot(10);
        assertEquals(1, debugSnapshot.entries().size());
    }

    @Test
    void dotTick_withUnknownStatusId_knownPartySource_prefersRecentCorroboratedActionInMultiTargetCase() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Dragoon"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(100),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(120),
                0x074F,
                "Dia",
                30.0,
                0x1000000CL,
                "WhiteMage",
                0x40000002L,
                "Add"
        ));
        service.onParsed(new StatusSnapshotRaw(
                base().plusMillis(150),
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41900000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Dragoon",
                0x64AC,
                "Chaotic Spring",
                0x40000001L,
                "Boss",
                false,
                false,
                12_345L,
                "21|...|raw"
        ));
        service.onParsed(new DotStatusSignalRaw(
                base().plusMillis(220),
                0x40000001L,
                List.of(new DotStatusSignalRaw.StatusSignal(0x0A9F, 0x1000000AL)),
                "37|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(5_000),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Dragoon",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertTrue(events.isEmpty());
        LiveDotAttributionDebugSnapshot debugSnapshot = service.debugLiveDotAttributionSnapshot(10);
        assertEquals("status0_corroborated_known_source", debugSnapshot.entries().get(0).mode());
    }

    @Test
    void dotTick_withUnknownStatusId_knownPartySource_staleCorroboratedActionDoesNotOverrideSuppressedFallback() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Dragoon"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(100),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(120),
                0x074F,
                "Dia",
                30.0,
                0x1000000CL,
                "WhiteMage",
                0x40000002L,
                "Add"
        ));
        service.onParsed(new StatusSnapshotRaw(
                base().plusMillis(150),
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41900000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Dragoon",
                0x64AC,
                "Chaotic Spring",
                0x40000001L,
                "Boss",
                false,
                false,
                12_345L,
                "21|...|raw"
        ));
        service.onParsed(new DotStatusSignalRaw(
                base().plusMillis(220),
                0x40000001L,
                List.of(new DotStatusSignalRaw.StatusSignal(0x0A9F, 0x1000000AL)),
                "37|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(20_500),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Dragoon",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertTrue(events.isEmpty());
        assertTrue(service.debugLiveDotAttributionSnapshot(10).entries().isEmpty());
    }

    @Test
    void dotTick_withUnknownStatusId_knownPartySource_doesNotSplitToMismatchedTrackedTargetActions() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Dragoon"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(100),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(120),
                0x074F,
                "Dia",
                30.0,
                0x1000000CL,
                "WhiteMage",
                0x40000001L,
                "Boss"
        ));
        service.onParsed(new BuffApplyRaw(
                base().plusMillis(140),
                0x0767,
                "Biolysis",
                30.0,
                0x1000000BL,
                "Scholar",
                0x40000002L,
                "Add"
        ));
        service.onParsed(new StatusSnapshotRaw(
                base().plusMillis(150),
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41900000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Dragoon",
                0x64AC,
                "Chaotic Spring",
                0x40000002L,
                "Add",
                false,
                false,
                12_345L,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(5_000),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Dragoon",
                10_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertTrue(events.isEmpty());
        assertTrue(service.debugLiveDotAttributionSnapshot(10).entries().isEmpty());
    }

    void dotTick_withUnknownStatusId_blendsRecentType37SignalsIntoSnapshotRedistribution() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Samurai"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Samurai",
                0x22,
                0,
                100_000L,
                100_000L,
                "03|...|Samurai"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));
        captured.clear();

        Instant snapshotTime = base().plusMillis(1_000);
        service.onParsed(new StatusSnapshotRaw(
                snapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x04CC, "41200000", 0x1000000AL), // SAM strong base
                        new StatusSnapshotRaw.StatusEntry(0x0767, "3F800000", 0x1000000BL), // SCH weak base
                        new StatusSnapshotRaw.StatusEntry(0x074F, "3F800000", 0x1000000CL)  // WHM weak base
                ),
                "38|...|raw"
        ));

        // 최근 type 37 신호가 SCH/WHM 쪽으로 반복해서 들어오는 상황을 만든다.
        service.onParsed(new DotStatusSignalRaw(
                snapshotTime.plusMillis(200),
                0x40000001L,
                List.of(
                        new DotStatusSignalRaw.StatusSignal(0x0767, 0x1000000BL),
                        new DotStatusSignalRaw.StatusSignal(0x074F, 0x1000000CL)
                ),
                "37|...|raw"
        ));
        service.onParsed(new DotStatusSignalRaw(
                snapshotTime.plusMillis(600),
                0x40000001L,
                List.of(
                        new DotStatusSignalRaw.StatusSignal(0x0767, 0x1000000BL),
                        new DotStatusSignalRaw.StatusSignal(0x074F, 0x1000000CL)
                ),
                "37|...|raw"
        ));

        captured.clear();
        service.onParsed(new DotTickRaw(
                snapshotTime.plusMillis(900),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Samurai",
                9_000,
                "24|...|raw"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(3, events.size());
        assertEquals(9_000L, events.stream().mapToLong(CombatEvent.DamageEvent::amount).sum());

        long samAmount = events.stream()
                .filter(event -> event.sourceId().equals(new ActorId(0x1000000AL)) && event.actionId() == 0x1D41)
                .mapToLong(CombatEvent.DamageEvent::amount)
                .sum();
        long schAmount = events.stream()
                .filter(event -> event.sourceId().equals(new ActorId(0x1000000BL)) && event.actionId() == 0x409C)
                .mapToLong(CombatEvent.DamageEvent::amount)
                .sum();
        long whmAmount = events.stream()
                .filter(event -> event.sourceId().equals(new ActorId(0x1000000CL)) && event.actionId() == 0x4094)
                .mapToLong(CombatEvent.DamageEvent::amount)
                .sum();

        assertTrue(schAmount > samAmount);
        assertTrue(whmAmount > samAmount);
    }

    @Test
    void debugLiveDotAttributionSnapshot_returnsRecentRollingAssignments() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Samurai"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL, 0x1000000CL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Samurai",
                0x22,
                0,
                100_000L,
                100_000L,
                "03|...|Samurai"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                100_000L,
                100_000L,
                "03|...|Scholar"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x1000000CL,
                "WhiteMage",
                0x18,
                0,
                100_000L,
                100_000L,
                "03|...|WhiteMage"
        ));

        Instant snapshotTime = base().plusMillis(1_000);
        service.onParsed(new StatusSnapshotRaw(
                snapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x04CC, "41200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41800000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        service.onParsed(new DotTickRaw(
                snapshotTime.plusMillis(200),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Samurai",
                9_000,
                "24|...|raw"
        ));

        Instant secondSnapshotTime = snapshotTime.plusSeconds(11);
        service.onParsed(new StatusSnapshotRaw(
                secondSnapshotTime,
                0x40000001L,
                "Boss",
                List.of(
                        new StatusSnapshotRaw.StatusEntry(0x04CC, "41200000", 0x1000000AL),
                        new StatusSnapshotRaw.StatusEntry(0x0767, "41800000", 0x1000000BL),
                        new StatusSnapshotRaw.StatusEntry(0x074F, "41800000", 0x1000000CL)
                ),
                "38|...|raw"
        ));
        service.onParsed(new DotTickRaw(
                secondSnapshotTime.plusMillis(200),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x1000000AL,
                "Samurai",
                9_000,
                "24|...|raw"
        ));

        LiveDotAttributionDebugSnapshot debugSnapshot = service.debugLiveDotAttributionSnapshot(10);

        assertEquals(3, debugSnapshot.recentAssignmentCount());
        assertEquals(3, debugSnapshot.entries().size());
        assertTrue(debugSnapshot.entries().stream().allMatch(entry -> "status0_snapshot_redistribution".equals(entry.mode())));
        assertTrue(debugSnapshot.entries().stream().allMatch(entry -> entry.totalAmount() > 0));
        assertTrue(debugSnapshot.entries().stream().noneMatch(entry -> entry.sourceId() == 0x1000000AL && entry.totalAmount() >= 9_000));
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
                "蹂댁뒪",
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
                "蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
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
                0x5EFA,
                "Eukrasian Dosis III",
                0x40000001L,
                "蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
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

        assertEquals(0x5EFA, event.actionId());
    }

    @Test
    void dotTick_withUnknownStatusId_forScholarBaneful_requiresRecentStatusApply() {
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

        service.onParsed(new BuffApplyRaw(
                base().plusMillis(300),
                0x0F2B,
                "Baneful Impaction",
                15.0,
                0x101C2E9EL,
                "Scholar",
                0x40000001L,
                "蹂댁뒪"
        ));

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBA0L,
                "24|...|raw"
        ));

        CombatEvent.DamageEvent event = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(0x9094, event.actionId());
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
                "蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusSeconds(91),
                0x40000001L,
                "蹂댁뒪",
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
    void dotTick_withUnknownStatusId_forTrackedJob_rejectsSourceFallbackWhenApplicationTargetChanged() {
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
                "?ㅻⅨ 蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        );
        assertFalse(service.wouldEmitDotDamage(dot));
        assertEquals(0, service.resolveDotActionId(dot));

        service.onParsed(dot);
        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_forTrackedJob_rejectsFallbackWhenOnlySameNameDifferentTargetExists() {
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
        service.onParsed(new CombatantAdded(
                base().plusMillis(60),
                0x40000001L,
                "Boss",
                0,
                0,
                50_000_000L,
                50_000_000L,
                "03|...|Boss"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(70),
                0x40000002L,
                "Boss",
                0,
                0,
                50_000_000L,
                50_000_000L,
                "03|...|Boss"
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
                "Boss",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        DotTickRaw dot = new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "Boss",
                "DoT",
                0,
                0x101C2E9EL,
                "Scholar",
                0xEBACL,
                "24|...|raw"
        );

        assertFalse(service.wouldEmitDotDamage(dot));
        assertEquals(0, service.resolveDotActionId(dot));

        service.onParsed(dot);
        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void dotTick_withUnknownStatusId_forDragoon_suppressesChaoticSpringDotTicksAfterRecentStatusApply() {
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
                "蹂댁뒪",
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
                "蹂댁뒪"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
                "DoT",
                0,
                0x101589A6L,
                "Dragoon",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void buffApply_forDragoonChaoticSpring_emitsClonedDirectDamageFromRecentApplicationHit() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Dragoon"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Dragoon",
                0x16,
                0,
                100_000L,
                100_000L,
                "03|...|Dragoon"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Dragoon",
                0x64AC,
                "Chaotic Spring",
                0x40000001L,
                "Boss",
                false,
                true,
                12_345L,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new BuffApplyRaw(
                base().plusMillis(500),
                0x0A9F,
                "Chaotic Spring",
                24.0,
                0x1000000AL,
                "Dragoon",
                0x40000001L,
                "Boss"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(1, events.size());
        assertEquals(DamageType.DIRECT, events.get(0).damageType());
        assertEquals(new ActorId(0x1000000AL), events.get(0).sourceId());
        assertEquals(new ActorId(0x40000001L), events.get(0).targetId());
        assertEquals(0x64AC, events.get(0).actionId());
        assertEquals(12_345L, events.get(0).amount());
        assertTrue(events.get(0).directHit());
    }

    @Test
    void buffApply_forSamuraiHiganbana_emitsClonedDirectDamageFromRecentApplicationHit() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Samurai"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000AL,
                "Samurai",
                0x22,
                0,
                100_000L,
                100_000L,
                "03|...|Samurai"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x1000000AL,
                "Samurai",
                0x1D41,
                "Higanbana",
                0x40000001L,
                "Boss",
                true,
                false,
                23_456L,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new BuffApplyRaw(
                base().plusMillis(500),
                0x04CC,
                "Higanbana",
                60.0,
                0x1000000AL,
                "Samurai",
                0x40000001L,
                "Boss"
        ));

        List<CombatEvent.DamageEvent> events = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .toList();

        assertEquals(1, events.size());
        assertEquals(DamageType.DIRECT, events.get(0).damageType());
        assertEquals(new ActorId(0x1000000AL), events.get(0).sourceId());
        assertEquals(new ActorId(0x40000001L), events.get(0).targetId());
        assertEquals(0x1D41, events.get(0).actionId());
        assertEquals(23_456L, events.get(0).amount());
        assertTrue(events.get(0).criticalHit());
    }

    @Test
    void dotTick_withUnknownStatusId_forWhiteMage_requiresRecentDiaApplication() {
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
                "蹂댁뒪",
                "DoT",
                0,
                0x10180001L,
                "WhiteMage",
                0xEBACL,
                "24|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(250),
                21,
                0x10180001L,
                "WhiteMage",
                0x4094,
                "Dia",
                0x40000001L,
                "蹂댁뒪",
                false,
                false,
                1200,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new DotTickRaw(
                base().plusMillis(3200),
                0x40000001L,
                "蹂댁뒪",
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
        assertEquals(0x4094, event.actionId());
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
    void networkAbility_withoutPartyData_fromOtherPlayerCharacter_isIgnored() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        captured.clear();

        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1000000BL,
                "Other Player",
                0x19,
                0,
                150_000L,
                150_000L,
                "261|...|Add"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000BL,
                "Other Player",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void networkAbility_withoutPartyData_fromCurrentPlayer_isAccepted() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Paladin",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));

        assertTrue(captured.stream().anyMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void networkAbility_withoutPartyData_fromOtherPlayerCharacter_isAcceptedDuringActiveSelfFight() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Paladin",
                0xB4,
                "Fast Blade",
                0x40000001L,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        captured.clear();

        service.onParsed(new CombatantAdded(
                base().plusMillis(150),
                0x10128857L,
                "Party Member",
                0x13,
                0,
                150_000L,
                150_000L,
                "261|...|Add"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(200),
                21,
                0x10128857L,
                "Party Member",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                6525,
                "21|...|raw"
        ));

        assertTrue(captured.stream().noneMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void networkAbility_withCombatDataReady_acceptsRestoredOtherPlayerBeforeFightStart() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        service.onCombatDataReady(2);
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x10128857L,
                "Party Member",
                0x13,
                0,
                150_000L,
                150_000L,
                "261|...|Add"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x10128857L,
                "Party Member",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                6525,
                "21|...|raw"
        ));

        assertTrue(captured.stream().anyMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void combatDataMetadataReady_restoresJobWithoutTrustingPartyMembership() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        service.onCombatDataReady(1, false);
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x10128857L,
                "Party Member",
                0x1C,
                0,
                150_000L,
                150_000L,
                "261|...|Add"
        ));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x10128857L,
                "Party Member",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                6525,
                "21|...|raw"
        ));

        assertEquals(0x1C, service.debugJobId(0x10128857L));
        assertFalse(captured.stream().anyMatch(CombatEvent.DamageEvent.class::isInstance));
    }

    @Test
    void playerStatsUpdated_setsCurrentPlayerJobBeforeFightStart() {
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "DarkKnight"));
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PlayerStatsUpdated(base().plusMillis(10), 0x20, "12|...|raw"));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "DarkKnight",
                0xE21,
                "Hard Slash",
                0x40000001L,
                "Boss",
                false,
                false,
                13001,
                "21|...|raw"
        ));

        CombatEvent.FightStart fightStart = captured.stream()
                .filter(CombatEvent.FightStart.class::isInstance)
                .map(CombatEvent.FightStart.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0x20, fightStart.playerJobId());
    }

    @Test
    void selfNetworkAbility_waitsForJobMetadataBeforeFightStart() {
        long playerId = 0x7000FFEEL;
        clearSelfJobCache(playerId, "MetadataWaiterFresh");
        service.onParsed(new PrimaryPlayerChanged(base(), playerId, "MetadataWaiterFresh"));
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                playerId,
                "MetadataWaiterFresh",
                0xE21,
                "Hard Slash",
                0x40000001L,
                "Boss",
                false,
                false,
                13001,
                "21|...|raw"
        ));

        assertTrue(captured.isEmpty());

        service.onParsed(new PlayerStatsUpdated(base().plusMillis(200), 0x20, "12|...|raw"));

        CombatEvent.FightStart fightStart = captured.stream()
                .filter(CombatEvent.FightStart.class::isInstance)
                .map(CombatEvent.FightStart.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0x20, fightStart.playerJobId());
        assertEquals(0x20, service.debugJobId(playerId));
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
                "Training Dummy",
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
                "Training Dummy",
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
                "Training Dummy",
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

        assertTrue(captured.stream().noneMatch(CombatEvent.BossIdentified.class::isInstance));
    }

    @Test
    void fightEnd_preservesCombatantContextForNextPullInSameZone() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Warrior"));
        service.onParsed(new PartyList(base(), List.of(0x1013CC4BL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(50),
                0x1013CC4BL,
                "?섏꽦",
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
                "?섏꽦",
                0x5EF8,
                "Player127",
                0x4000664CL,
                "蹂댁뒪",
                false,
                false,
                1000,
                "21|..."
        ));
        service.onParsed(new NetworkDeath(
                base().plusMillis(200),
                0x1013CC4BL,
                "?섏꽦"
        ));

        captured.clear();

        service.onParsed(new NetworkAbilityRaw(
                base().plusSeconds(40),
                21,
                0x1013CC4BL,
                "?섏꽦",
                0x5EF8,
                "Player127",
                0x4000664CL,
                "蹂댁뒪",
                false,
                false,
                1000,
                "21|..."
        ));
        service.onParsed(new DotTickRaw(
                base().plusSeconds(41),
                0x4000664CL,
                "蹂댁뒪",
                "DoT",
                0,
                0x1013CC4BL,
                "?섏꽦",
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

    @Test
    void currentPlayerJobChange_duringFight_endsFightAndNextPullUsesNewJob() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(10),
                0x1000000AL,
                "Paladin",
                0x13,
                0,
                180_000L,
                180_000L,
                "261|...|Add"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Paladin",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                6525,
                "21|...|raw"
        ));

        assertTrue(service.isFightStarted());
        captured.clear();

        service.onParsed(new CombatantAdded(
                base().plusMillis(200),
                0x1000000AL,
                "BlackMage",
                0x19,
                0,
                160_000L,
                160_000L,
                "261|...|Add"
        ));

        assertFalse(service.isFightStarted());
        assertTrue(captured.stream().anyMatch(CombatEvent.FightEnd.class::isInstance));

        captured.clear();
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(300),
                21,
                0x1000000AL,
                "BlackMage",
                0x8A,
                "Fire",
                0x40000001L,
                "Boss",
                false,
                false,
                9000,
                "21|...|raw"
        ));

        CombatEvent.FightStart nextFightStart = captured.stream()
                .filter(CombatEvent.FightStart.class::isInstance)
                .map(CombatEvent.FightStart.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0x19, nextFightStart.playerJobId());
    }

    @Test
    void partyWipe_endsFightAndNextPullStartsFreshWithoutCarryingPreviousDamage() {
        service.onParsed(new ZoneChanged(base(), 1226, "Test Zone"));
        service.onParsed(new PrimaryPlayerChanged(base(), 0x1000000AL, "Paladin"));
        service.onParsed(new PartyList(base(), List.of(0x1000000AL, 0x1000000BL)));
        service.onParsed(new CombatantAdded(
                base().plusMillis(10),
                0x1000000AL,
                "Paladin",
                0x13,
                0,
                180_000L,
                180_000L,
                "261|...|Add"
        ));
        service.onParsed(new CombatantAdded(
                base().plusMillis(20),
                0x1000000BL,
                "Scholar",
                0x1C,
                0,
                180_000L,
                180_000L,
                "261|...|Add"
        ));

        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(100),
                21,
                0x1000000AL,
                "Paladin",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                5000,
                "21|...|raw"
        ));
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(120),
                21,
                0x1000000BL,
                "Scholar",
                0x409C,
                "Biolysis",
                0x40000001L,
                "Boss",
                false,
                false,
                6000,
                "21|...|raw"
        ));

        assertTrue(service.isFightStarted());
        captured.clear();

        service.onParsed(new NetworkDeath(
                base().plusMillis(200),
                0x1000000AL,
                "Paladin"
        ));
        assertTrue(service.isFightStarted());

        service.onParsed(new NetworkDeath(
                base().plusMillis(220),
                0x1000000BL,
                "Scholar"
        ));

        assertFalse(service.isFightStarted());
        assertTrue(captured.stream().anyMatch(CombatEvent.FightEnd.class::isInstance));

        captured.clear();
        service.onParsed(new NetworkAbilityRaw(
                base().plusMillis(400),
                21,
                0x1000000AL,
                "Paladin",
                0x18,
                "Shield Lob",
                0x40000001L,
                "Boss",
                false,
                false,
                7000,
                "21|...|raw"
        ));

        CombatEvent.FightStart nextFightStart = captured.stream()
                .filter(CombatEvent.FightStart.class::isInstance)
                .map(CombatEvent.FightStart.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0L, nextFightStart.timestampMs());

        CombatEvent.DamageEvent firstDamage = captured.stream()
                .filter(CombatEvent.DamageEvent.class::isInstance)
                .map(CombatEvent.DamageEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0L, firstDamage.timestampMs());
        assertEquals(7000L, firstDamage.amount());
    }
}

