package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.CombatState;
import com.bohouse.pacemeter.core.model.DamageType;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.snapshot.ActorSnapshot;
import com.bohouse.pacemeter.core.snapshot.ClearabilityCheck;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider.ConfidenceLevel;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider.EnrageInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 리플레이 기반 회귀 테스트.
 *
 * JSONL 파일에 저장된 전투 로그를 엔진에 넣고,
 * 나오는 스냅샷이 기대한 값과 같은지 검증한다.
 *
 * 이렇게 하면:
 *   - 결정론성: 같은 입력 → 항상 같은 출력 보장
 *   - 회귀 방지: 코어 로직을 바꿨을 때 결과가 달라지면 바로 잡힘
 *   - 문서화: JSONL 파일 자체가 "실행 가능한 스펙" 역할
 */
class CombatEngineReplayTest {

    @Test
    void basicFightReplay_producesExpectedSnapshots() throws IOException {
        // 준비: JSONL 리플레이 파일을 읽어서 이벤트 리스트로 변환
        List<CombatEvent> events = ReplayEventParser.parseResource("/replay/basic_fight.jsonl");
        CombatEngine engine = new CombatEngine();

        // 실행: 모든 이벤트를 엔진에 넣고, 스냅샷이 나올 때마다 수집
        List<OverlaySnapshot> snapshots = new ArrayList<>();
        for (CombatEvent event : events) {
            EngineResult result = engine.process(event);
            result.snapshot().ifPresent(snapshots::add);
        }

        // 검증: 틱 4개(2500, 5000, 7500, 20000) + FightEnd 1개 = 총 5개 스냅샷
        assertEquals(5, snapshots.size(), "틱 4개 + 마지막 스냅샷 1개 = 5개여야 함");

        // 첫 번째 스냅샷 (2500ms 시점 틱): 3명의 캐릭터가 초기 데미지를 줌
        OverlaySnapshot first = snapshots.get(0);
        assertEquals("The Futures Rewritten", first.fightName());
        assertEquals(CombatState.Phase.ACTIVE, first.phase());
        assertEquals(2500, first.elapsedMs());
        assertEquals("0:02", first.elapsedFormatted());
        assertFalse(first.isFinal());
        assertEquals(3, first.actors().size());

        // 2500ms 시점 총 데미지: 12000(전사) + 28000(흑마) + 18000(몽크) = 58000
        assertEquals(58000, first.totalPartyDamage());

        // 캐릭터는 데미지 높은 순으로 정렬되어야 함
        // 흑마: 28000 > 몽크: 18000 > 전사: 12000
        assertEquals("Black Mage", first.actors().get(0).name());
        assertEquals(28000, first.actors().get(0).totalDamage());
        assertEquals("Monk", first.actors().get(1).name());
        assertEquals(18000, first.actors().get(1).totalDamage());
        assertEquals("Warrior Main", first.actors().get(2).name());
        assertEquals(12000, first.actors().get(2).totalDamage());

        // DPS 계산 검증: 흑마 28000 / 2.5초 = 11200
        assertEquals(11200.0, first.actors().get(0).dps(), 0.1);

        // 전투 시작 2.5초밖에 안 됐으므로 모든 캐릭터의 신뢰도가 깎여야 함
        for (ActorSnapshot actor : first.actors()) {
            assertTrue(actor.rdpsConfidence().score() < 1.0,
                    "짧은 전투이므로 신뢰도가 1.0 미만이어야 함: " + actor.name());
        }

        // 마지막 스냅샷은 FightEnd에 의한 최종 스냅샷
        OverlaySnapshot last = snapshots.get(snapshots.size() - 1);
        assertTrue(last.isFinal());
        assertEquals(CombatState.Phase.ENDED, last.phase());
        assertEquals(20000, last.elapsedMs());

        // 최종 총 데미지 합산 검증:
        // 전사: 12000+14000+8000+13000+11000 = 58000
        // 흑마: 28000+32000+30000+35000 = 125000
        // 몽크: 18000+20000+19000 = 57000
        // 합계: 240000
        assertEquals(240000, last.totalPartyDamage());

        // 최종 파티 DPS: 240000 / 20초 = 12000
        assertEquals(12000.0, last.partyDps(), 0.1);
        assertNull(last.clearability());
    }

    @Test
    void fightStart_resetsState() throws IOException {
        CombatEngine engine = new CombatEngine();

        // 전투 시작 전에 데미지 이벤트를 보내면 → 무시되어야 함 (IDLE 상태이므로)
        engine.process(new CombatEvent.DamageEvent(
                100, new ActorId(1), "Test", new ActorId(100), 1, 5000,
                DamageType.DIRECT, false, false));

        assertEquals(CombatState.Phase.IDLE, engine.currentState().phase());
        assertEquals(0, engine.currentState().totalPartyDamage());

        // 전투 시작 이벤트를 보내면 → ACTIVE 상태로 전환
        engine.process(new CombatEvent.FightStart(0, "Test Fight", 0, 0));
        assertEquals(CombatState.Phase.ACTIVE, engine.currentState().phase());
        assertEquals("Test Fight", engine.currentState().fightName());
    }

    @Test
    void tickProducesSnapshot_damageDoesNot() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));

        // 데미지 이벤트는 스냅샷을 만들지 않는다
        EngineResult damageResult = engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 10000,
                DamageType.DIRECT, false, false));
        assertFalse(damageResult.hasSnapshot());

        // 틱 이벤트는 스냅샷을 만든다
        EngineResult tickResult = engine.process(new CombatEvent.Tick(1250));
        assertTrue(tickResult.hasSnapshot());
    }

    @Test
    void determinism_sameInputSameOutput() throws IOException {
        List<CombatEvent> events = ReplayEventParser.parseResource("/replay/basic_fight.jsonl");

        // 같은 입력으로 2번 실행
        List<OverlaySnapshot> run1 = runReplay(events);
        List<OverlaySnapshot> run2 = runReplay(events);

        // 결과가 완전히 동일해야 한다 (결정론적)
        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            OverlaySnapshot s1 = run1.get(i);
            OverlaySnapshot s2 = run2.get(i);

            assertEquals(s1.elapsedMs(), s2.elapsedMs());
            assertEquals(s1.totalPartyDamage(), s2.totalPartyDamage());
            assertEquals(s1.partyDps(), s2.partyDps(), 0.001);
            assertEquals(s1.actors().size(), s2.actors().size());

            for (int j = 0; j < s1.actors().size(); j++) {
                ActorSnapshot a1 = s1.actors().get(j);
                ActorSnapshot a2 = s2.actors().get(j);
                assertEquals(a1.actorId(), a2.actorId());
                assertEquals(a1.totalDamage(), a2.totalDamage());
                assertEquals(a1.dps(), a2.dps(), 0.001);
                assertEquals(a1.onlineRdps(), a2.onlineRdps(), 0.001);
                assertEquals(a1.rdpsConfidence().score(), a2.rdpsConfidence().score(), 0.001);
            }
        }
    }

    @Test
    void paceComparison_withProfile() {
        // 간단한 선형 페이스 프로필: 초당 10000 데미지 기준
        PaceProfile linearProfile =
                new PaceProfile() {
                    @Override public long expectedCumulativeDamage(long elapsedMs) {
                        return elapsedMs * 10; // 10000 DPS = 1ms당 10 데미지
                    }
                    @Override public String label() { return "linear_10k"; }
                    @Override public long totalDurationMs() { return 120_000; }
                };

        CombatEngine engine = new CombatEngine(linearProfile);
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));

        // 1초에 15000 데미지 (기준 10000보다 앞서고 있음)
        engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 15000,
                DamageType.DIRECT, false, false));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());

        OverlaySnapshot snapshot = result.snapshot().get();
        assertNotNull(snapshot.partyPace());
        assertEquals("linear_10k", snapshot.partyPace().profileLabel());
        assertEquals(10000, snapshot.partyPace().expectedCumulativeDamage());
        assertEquals(15000, snapshot.partyPace().actualCumulativeDamage());
        assertEquals(5000, snapshot.partyPace().deltaDamage());
        assertTrue(snapshot.partyPace().deltaPercent() > 0, "페이스보다 앞서야 함");
    }

    @Test
    void noProfileLoaded_paceComparisonIsNull() {
        CombatEngine engine = new CombatEngine(); // 프로필 없음
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 10000,
                DamageType.DIRECT, false, false));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());
        assertNull(result.snapshot().get().partyPace());
        assertNull(result.snapshot().get().clearability());
    }

    @Test
    void noIndividualProfile_currentPlayerStillExposedWithoutMarker() {
        CombatEngine engine = new CombatEngine();
        engine.setCurrentPlayerId(new ActorId(1));
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 10000,
                DamageType.DIRECT, false, false));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());

        ActorSnapshot actor = result.snapshot().orElseThrow().actors().get(0);
        assertTrue(actor.isCurrentPlayer());
        assertNull(actor.individualPace());
    }

    @Test
    void onlineRdps_usesFixedRecentWindow_toAvoidBurstSpike() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Ninja"));
        engine.process(new CombatEvent.DamageEvent(
                1_000, new ActorId(1), "Ninja", new ActorId(100), 1, 300_000,
                DamageType.DIRECT, false, false));
        engine.process(new CombatEvent.Tick(1_000));
        engine.process(new CombatEvent.DamageEvent(
                19_900, new ActorId(1), "Ninja", new ActorId(100), 2, 120_000,
                DamageType.DIRECT, false, false));
        engine.process(new CombatEvent.DamageEvent(
                20_000, new ActorId(1), "Ninja", new ActorId(100), 3, 120_000,
                DamageType.DIRECT, false, false));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(20_000)).snapshot().orElseThrow();
        ActorSnapshot actor = snapshot.actors().stream()
                .filter(entry -> entry.name().equals("Ninja"))
                .findFirst()
                .orElseThrow();

        assertEquals(27_000.0, actor.dps(), 0.1);
        assertEquals(16_000.0, actor.recentDps(), 0.1);
        assertEquals(23_088.9, actor.onlineRdps(), 0.1);
    }

    @Test
    void bossIdentified_updatesCombatState() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));

        engine.process(new CombatEvent.BossIdentified(
                100,
                new ActorId(0x40000001L),
                "Test Boss",
                123_456_789L
        ));

        assertTrue(engine.currentState().bossInfo().isPresent());
        CombatState.BossInfo bossInfo = engine.currentState().bossInfo().orElseThrow();
        assertEquals(new ActorId(0x40000001L), bossInfo.actorId());
        assertEquals("Test Boss", bossInfo.name());
        assertEquals(123_456_789L, bossInfo.maxHp());
    }

    @Test
    void clearability_isCalculatedWhenBossAndEnrageExist() {
        CombatEngine engine = new CombatEngine();
        engine.setEnrageInfo(Optional.of(new EnrageInfo(
                20.0,
                ConfidenceLevel.MEDIUM,
                "test"
        )));
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.BossIdentified(0, new ActorId(100), "Boss", 100_000));
        engine.process(new CombatEvent.DamageEvent(
                5000, new ActorId(1), "Player", new ActorId(100), 1, 50_000,
                DamageType.DIRECT, false, false));

        EngineResult result = engine.process(new CombatEvent.Tick(10000));
        assertTrue(result.hasSnapshot());

        ClearabilityCheck clearability = result.snapshot().orElseThrow().clearability();
        assertNotNull(clearability);
        assertTrue(clearability.canClear());
        assertEquals(20.0, clearability.estimatedKillTimeSeconds(), 0.001);
        assertEquals(20.0, clearability.enrageTimeSeconds(), 0.001);
        assertEquals(0.0, clearability.marginSeconds(), 0.001);
        assertEquals(5000.0, clearability.requiredDps(), 0.001);
    }

    @Test
    void externalDamageBuff_redistributesBonusToProvider() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Dancer"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Astrologian"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Dancer",
                new ActorId(100),
                1,
                10_600,
                DamageType.DIRECT,
                false,
                false
        ));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());

        OverlaySnapshot snapshot = result.snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream()
                .filter(actor -> actor.name().equals("Dancer"))
                .findFirst()
                .orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream()
                .filter(actor -> actor.name().equals("Astrologian"))
                .findFirst()
                .orElseThrow();

        assertEquals(10_000.0, dealer.onlineRdps(), 1.0);
        assertEquals(600.0, provider.onlineRdps(), 1.0);
    }

    @Test
    void stackedExternalDamageBuffs_shareContributionAcrossProviders() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Dancer"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Astrologian A"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(3), "Astrologian B"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(3),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Dancer",
                new ActorId(100),
                1,
                11_236,
                DamageType.DIRECT,
                false,
                false
        ));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        OverlaySnapshot snapshot = result.snapshot().orElseThrow();

        ActorSnapshot dealer = snapshot.actors().stream()
                .filter(actor -> actor.name().equals("Dancer"))
                .findFirst()
                .orElseThrow();
        ActorSnapshot providerA = snapshot.actors().stream()
                .filter(actor -> actor.name().equals("Astrologian A"))
                .findFirst()
                .orElseThrow();
        ActorSnapshot providerB = snapshot.actors().stream()
                .filter(actor -> actor.name().equals("Astrologian B"))
                .findFirst()
                .orElseThrow();

        assertEquals(10_000.0, dealer.onlineRdps(), 2.0);
        assertEquals(618.0, providerA.onlineRdps(), 2.0);
        assertEquals(618.0, providerB.onlineRdps(), 2.0);
    }

    @Test
    void critRateBuff_redistributesExpectedContributionToProvider() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Dragoon"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Bard"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0xFFFF),
                "Battle Litany",
                15_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Dragoon",
                new ActorId(100),
                1,
                14_000,
                DamageType.DIRECT,
                true,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Dragoon")).findFirst().orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream().filter(a -> a.name().equals("Bard")).findFirst().orElseThrow();

        assertEquals(12_500.0, dealer.onlineRdps(), 2.0);
        assertEquals(1_500.0, provider.onlineRdps(), 2.0);
    }

    @Test
    void directHitRateBuff_redistributesExpectedContributionToProvider() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Monk"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Bard"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0xFFFF),
                "Battle Voice",
                15_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Monk",
                new ActorId(100),
                1,
                12_500,
                DamageType.DIRECT,
                false,
                true
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Monk")).findFirst().orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream().filter(a -> a.name().equals("Bard")).findFirst().orElseThrow();

        assertEquals(11_071.4, dealer.onlineRdps(), 2.0);
        assertEquals(1_428.6, provider.onlineRdps(), 2.0);
    }

    @Test
    void targetDamageDebuff_redistributesContributionToProvider() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Samurai"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Ninja"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(100),
                new BuffId(0xFFFF),
                "Mug",
                20_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Samurai",
                new ActorId(100),
                1,
                10_500,
                DamageType.DIRECT,
                false,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Samurai")).findFirst().orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream().filter(a -> a.name().equals("Ninja")).findFirst().orElseThrow();

        assertEquals(10_000.0, dealer.onlineRdps(), 2.0);
        assertEquals(500.0, provider.onlineRdps(), 2.0);
    }

    @Test
    void targetCritDebuff_redistributesContributionToProvider() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Black Mage"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Scholar"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(100),
                new BuffId(0xFFFF),
                "Chain Stratagem",
                15_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Black Mage",
                new ActorId(100),
                1,
                14_000,
                DamageType.DIRECT,
                true,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Black Mage")).findFirst().orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream().filter(a -> a.name().equals("Scholar")).findFirst().orElseThrow();

        assertEquals(12_500.0, dealer.onlineRdps(), 2.0);
        assertEquals(1_500.0, provider.onlineRdps(), 2.0);
    }

    @Test
    void dotSnapshot_preservesBuffAttributionAfterBuffFallsOff() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Black Mage"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Astrologian"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.BuffApply(
                100,
                new ActorId(1),
                new ActorId(0x40000001L),
                new BuffId(0xFFFE),
                "Combust III",
                30_000
        ));
        engine.process(new CombatEvent.BuffRemove(
                500,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance"
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Black Mage",
                new ActorId(0x40000001L),
                0xFFFE,
                10_600,
                DamageType.DOT,
                false,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Black Mage")).findFirst().orElseThrow();
        ActorSnapshot provider = snapshot.actors().stream().filter(a -> a.name().equals("Astrologian")).findFirst().orElseThrow();

        assertEquals(10_000.0, dealer.onlineRdps(), 1.0);
        assertEquals(600.0, provider.onlineRdps(), 1.0);
    }

    @Test
    void dotSnapshot_usesStatusIdToSeparateMultipleDots() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Summoner"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(2), "Astrologian A"));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(3), "Astrologian B"));

        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.BuffApply(
                100,
                new ActorId(1),
                new ActorId(0x40000001L),
                new BuffId(0x2ED),
                "Dot A",
                30_000
        ));
        engine.process(new CombatEvent.BuffRemove(
                200,
                new ActorId(2),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance"
        ));

        engine.process(new CombatEvent.BuffApply(
                300,
                new ActorId(3),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance",
                15_000
        ));
        engine.process(new CombatEvent.BuffApply(
                400,
                new ActorId(1),
                new ActorId(0x40000001L),
                new BuffId(0x35D),
                "Dot B",
                30_000
        ));
        engine.process(new CombatEvent.BuffRemove(
                500,
                new ActorId(3),
                new ActorId(1),
                new BuffId(0x74F),
                "The Balance"
        ));

        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Summoner",
                new ActorId(0x40000001L),
                0x35D,
                10_600,
                DamageType.DOT,
                false,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dealer = snapshot.actors().stream().filter(a -> a.name().equals("Summoner")).findFirst().orElseThrow();
        ActorSnapshot providerA = snapshot.actors().stream().filter(a -> a.name().equals("Astrologian A")).findFirst().orElseThrow();
        ActorSnapshot providerB = snapshot.actors().stream().filter(a -> a.name().equals("Astrologian B")).findFirst().orElseThrow();

        assertEquals(10_000.0, dealer.onlineRdps(), 1.0);
        assertEquals(0.0, providerA.onlineRdps(), 1.0);
        assertEquals(600.0, providerB.onlineRdps(), 1.0);
    }

    @Test
    void selfAppliedRaidBuff_doesNotCountAsExternalContribution() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test", 0, 0));
        engine.process(new CombatEvent.ActorJoined(0, new ActorId(1), "Dancer"));
        engine.process(new CombatEvent.BuffApply(
                0,
                new ActorId(1),
                new ActorId(1),
                new BuffId(1821),
                "정석 마무리",
                60_000
        ));
        engine.process(new CombatEvent.DamageEvent(
                1000,
                new ActorId(1),
                "Dancer",
                new ActorId(0x40000001L),
                0x3E85,
                10_500,
                DamageType.DIRECT,
                false,
                false
        ));

        OverlaySnapshot snapshot = engine.process(new CombatEvent.Tick(1000)).snapshot().orElseThrow();
        ActorSnapshot dancer = snapshot.actors().stream().filter(a -> a.name().equals("Dancer")).findFirst().orElseThrow();

        assertEquals(10_500.0, dancer.onlineRdps(), 1.0);
    }

    /** 이벤트 리스트를 엔진에 넣고 나온 스냅샷들을 수집하는 헬퍼 메서드 */
    private List<OverlaySnapshot> runReplay(List<CombatEvent> events) {
        CombatEngine engine = new CombatEngine();
        List<OverlaySnapshot> snapshots = new ArrayList<>();
        for (CombatEvent event : events) {
            engine.process(event).snapshot().ifPresent(snapshots::add);
        }
        return snapshots;
    }
}
