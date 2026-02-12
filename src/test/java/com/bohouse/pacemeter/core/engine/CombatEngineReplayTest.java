package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.CombatState;
import com.bohouse.pacemeter.core.snapshot.ActorSnapshot;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    }

    @Test
    void fightStart_resetsState() throws IOException {
        CombatEngine engine = new CombatEngine();

        // 전투 시작 전에 데미지 이벤트를 보내면 → 무시되어야 함 (IDLE 상태이므로)
        engine.process(new CombatEvent.DamageEvent(
                100, new ActorId(1), "Test", new ActorId(100), 1, 5000,
                com.bohouse.pacemeter.core.model.DamageType.DIRECT));

        assertEquals(CombatState.Phase.IDLE, engine.currentState().phase());
        assertEquals(0, engine.currentState().totalPartyDamage());

        // 전투 시작 이벤트를 보내면 → ACTIVE 상태로 전환
        engine.process(new CombatEvent.FightStart(0, "Test Fight"));
        assertEquals(CombatState.Phase.ACTIVE, engine.currentState().phase());
        assertEquals("Test Fight", engine.currentState().fightName());
    }

    @Test
    void tickProducesSnapshot_damageDoesNot() {
        CombatEngine engine = new CombatEngine();
        engine.process(new CombatEvent.FightStart(0, "Test"));

        // 데미지 이벤트는 스냅샷을 만들지 않는다
        EngineResult damageResult = engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 10000,
                com.bohouse.pacemeter.core.model.DamageType.DIRECT));
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
        com.bohouse.pacemeter.core.estimator.PaceProfile linearProfile =
                new com.bohouse.pacemeter.core.estimator.PaceProfile() {
                    @Override public long expectedCumulativeDamage(long elapsedMs) {
                        return elapsedMs * 10; // 10000 DPS = 1ms당 10 데미지
                    }
                    @Override public String label() { return "linear_10k"; }
                    @Override public long totalDurationMs() { return 120_000; }
                };

        CombatEngine engine = new CombatEngine(linearProfile);
        engine.process(new CombatEvent.FightStart(0, "Test"));

        // 1초에 15000 데미지 (기준 10000보다 앞서고 있음)
        engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 15000,
                com.bohouse.pacemeter.core.model.DamageType.DIRECT));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());

        OverlaySnapshot snapshot = result.snapshot().get();
        assertNotNull(snapshot.paceComparison());
        assertEquals("linear_10k", snapshot.paceComparison().profileLabel());
        assertEquals(10000, snapshot.paceComparison().expectedCumulativeDamage());
        assertEquals(15000, snapshot.paceComparison().actualCumulativeDamage());
        assertEquals(5000, snapshot.paceComparison().deltaDamage());
        assertTrue(snapshot.paceComparison().deltaPercent() > 0, "페이스보다 앞서야 함");
    }

    @Test
    void noProfileLoaded_paceComparisonIsNull() {
        CombatEngine engine = new CombatEngine(); // 프로필 없음
        engine.process(new CombatEvent.FightStart(0, "Test"));
        engine.process(new CombatEvent.DamageEvent(
                1000, new ActorId(1), "Player", new ActorId(100), 1, 10000,
                com.bohouse.pacemeter.core.model.DamageType.DIRECT));

        EngineResult result = engine.process(new CombatEvent.Tick(1000));
        assertTrue(result.hasSnapshot());
        assertNull(result.snapshot().get().paceComparison());
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
