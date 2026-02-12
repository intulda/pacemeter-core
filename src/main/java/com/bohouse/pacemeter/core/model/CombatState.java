package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전투의 전체 상태를 관리하는 핵심 클래스.
 *
 * 전투 엔진의 "두뇌" 역할로, 모든 이벤트를 받아서 상태를 업데이트한다.
 * 예를 들어 데미지 이벤트가 오면 해당 캐릭터의 총 데미지를 올리고,
 * 틱 이벤트가 오면 "지금 현재 상태를 스냅샷으로 찍어야 해"라고 알려준다.
 *
 * 전투 상태의 생명주기:
 *   IDLE(대기) -> ACTIVE(전투 중) -> ENDED(전투 종료) -> 다시 IDLE(다음 전투 대기)
 *
 * 이 클래스는 변경 가능(mutable)하다. reduce() 메서드가 직접 상태를 바꾼다.
 * 단, 엔진이 싱글 스레드이므로 동시 접근 문제는 없다.
 */
public final class CombatState {

    /** 전투 진행 단계. IDLE=대기, ACTIVE=전투중, ENDED=전투종료 */
    public enum Phase { IDLE, ACTIVE, ENDED }

    private Phase phase;
    private String fightName;
    private long fightStartMs;     // 전투 시작 시각 (어댑터용, 코어에서는 직접 사용하지 않음)
    private long elapsedMs;        // 전투 시작 이후 경과 시간 (밀리초)
    private long totalPartyDamage;

    /** 캐릭터별 통계. 먼저 등장한 순서대로 저장된다. */
    private final Map<ActorId, ActorStats> actors;

    /** 최근 DPS 계산에 사용할 슬라이딩 윈도우 크기 (밀리초). 기본 15초. */
    public static final long RECENT_WINDOW_MS = 15_000;

    public CombatState() {
        this.phase = Phase.IDLE;
        this.fightName = "";
        this.fightStartMs = 0;
        this.elapsedMs = 0;
        this.totalPartyDamage = 0;
        this.actors = new LinkedHashMap<>();
    }

    // ========================================================================
    // 상태 변환: 이벤트를 받아서 상태를 업데이트하고, 스냅샷이 필요하면 true 반환
    // ========================================================================

    /**
     * 이벤트 하나를 받아서 전투 상태를 업데이트한다.
     *
     * Tick이나 FightEnd 이벤트일 때만 true를 반환한다.
     * true가 반환되면 "지금 스냅샷을 만들어서 오버레이에 보내야 한다"는 뜻이다.
     */
    public boolean reduce(CombatEvent event) {
        if (event instanceof CombatEvent.FightStart e) {
            return reduceFightStart(e);
        } else if (event instanceof CombatEvent.DamageEvent e) {
            return reduceDamage(e);
        } else if (event instanceof CombatEvent.BuffApply e) {
            return reduceBuffApply(e);
        } else if (event instanceof CombatEvent.BuffRemove e) {
            return reduceBuffRemove(e);
        } else if (event instanceof CombatEvent.Tick e) {
            return reduceTick(e);
        } else if (event instanceof CombatEvent.FightEnd e) {
            return reduceFightEnd(e);
        }
        throw new IllegalArgumentException("알 수 없는 이벤트 타입: " + event.getClass().getName());
    }

    /** 전투 시작: 모든 상태를 초기화하고 ACTIVE로 전환 */
    private boolean reduceFightStart(CombatEvent.FightStart e) {
        this.phase = Phase.ACTIVE;
        this.fightName = e.fightName();
        this.fightStartMs = e.timestampMs();
        this.elapsedMs = 0;
        this.totalPartyDamage = 0;
        this.actors.clear();
        return false;
    }

    /** 데미지 이벤트: 해당 캐릭터의 데미지를 누적 */
    private boolean reduceDamage(CombatEvent.DamageEvent e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();

        // 처음 보는 캐릭터면 새로 등록, 기존 캐릭터면 기존 통계를 가져옴
        ActorStats stats = actors.computeIfAbsent(e.sourceId(),
                id -> new ActorStats(id, e.sourceName()));

        // 이름이 비어있었는데 이번에 이름이 들어왔으면 업데이트
        if (stats.name().isEmpty() && !e.sourceName().isEmpty()) {
            stats.setName(e.sourceName());
        }

        stats.addDamage(e.amount(), e.timestampMs());
        this.totalPartyDamage += e.amount();

        return false;
    }

    /** 버프 적용: 대상 캐릭터의 버프 목록에 추가 */
    private boolean reduceBuffApply(CombatEvent.BuffApply e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();

        // 버프는 "받는 쪽(target)" 캐릭터에 기록한다
        ActorStats target = actors.computeIfAbsent(e.targetId(),
                id -> new ActorStats(id, ""));

        target.applyBuff(new ActiveBuff(e.buffId(), e.sourceId(), e.timestampMs(), e.durationMs()));

        return false;
    }

    /** 버프 제거: 대상 캐릭터의 버프 목록에서 삭제 */
    private boolean reduceBuffRemove(CombatEvent.BuffRemove e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();

        ActorStats target = actors.get(e.targetId());
        if (target != null) {
            target.removeBuff(e.buffId(), e.sourceId());
        }

        return false;
    }

    /**
     * 틱 이벤트: 각 캐릭터의 오래된 데미지 기록을 정리하고, 스냅샷 생성을 요청한다.
     * 약 250ms마다 호출되어 오버레이 화면을 갱신하는 트리거 역할.
     */
    private boolean reduceTick(CombatEvent.Tick e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();

        // 슬라이딩 윈도우: 현재 시각 - 15초보다 오래된 데미지 기록 삭제
        long cutoff = e.timestampMs() - RECENT_WINDOW_MS;
        for (ActorStats stats : actors.values()) {
            stats.pruneOldSamples(cutoff);
        }

        return true;  // 스냅샷을 만들어라!
    }

    /** 전투 종료: 상태를 ENDED로 바꾸고 마지막 스냅샷 생성을 요청한다. */
    private boolean reduceFightEnd(CombatEvent.FightEnd e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        this.phase = Phase.ENDED;

        return true;  // 마지막 스냅샷을 만들어라!
    }

    // ========================================================================
    // 읽기 전용 접근자 (Aggregator, Estimator 등에서 사용)
    // ========================================================================

    public Phase phase() { return phase; }
    public String fightName() { return fightName; }
    public long elapsedMs() { return elapsedMs; }
    public long totalPartyDamage() { return totalPartyDamage; }

    /** 등록된 모든 캐릭터의 통계를 반환 (수정 불가) */
    public Map<ActorId, ActorStats> actors() {
        return Collections.unmodifiableMap(actors);
    }

    /** 특정 캐릭터의 통계를 반환. 없으면 null. */
    public ActorStats getActor(ActorId id) {
        return actors.get(id);
    }
}
