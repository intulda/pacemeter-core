package com.bohouse.pacemeter.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 한 캐릭터(액터)의 전투 통계를 누적하는 클래스.
 *
 * CombatState 안에서 캐릭터마다 하나씩 만들어진다.
 * 예: 전사의 ActorStats에는 전사가 지금까지 준 총 데미지, 타격 횟수,
 *     현재 걸린 버프 목록, 최근 데미지 기록 등이 들어있다.
 *
 * 이 클래스는 성능을 위해 "변경 가능(mutable)"하게 설계되었다.
 * 단, 엔진이 싱글 스레드로 동작하기 때문에 동시 접근 문제는 없다.
 * 오버레이에 데이터를 보낼 때는 값을 복사해서 스냅샷을 만든다.
 */
public final class ActorStats {

    private final ActorId actorId;
    private String name;
    private long totalDamage;
    private int hitCount;
    private final List<ActiveBuff> activeBuffs;

    /** 최근 데미지 기록 목록. 슬라이딩 윈도우 DPS 계산에 사용된다. */
    private final List<DamageSample> recentSamples;

    public ActorStats(ActorId actorId, String name) {
        this.actorId = actorId;
        this.name = name;
        this.totalDamage = 0;
        this.hitCount = 0;
        this.activeBuffs = new ArrayList<>();
        this.recentSamples = new ArrayList<>();
    }

    /** 스냅샷용 깊은 복사 생성자. 원본을 그대로 두고 사본을 만든다. */
    public ActorStats(ActorStats other) {
        this.actorId = other.actorId;
        this.name = other.name;
        this.totalDamage = other.totalDamage;
        this.hitCount = other.hitCount;
        this.activeBuffs = new ArrayList<>(other.activeBuffs);        // record는 불변이라 얕은 복사로 충분
        this.recentSamples = new ArrayList<>(other.recentSamples);    // record는 불변이라 얕은 복사로 충분
    }

    /** 데미지를 누적한다. 총 데미지 증가 + 타격 횟수 증가 + 최근 기록에 추가. */
    public void addDamage(long amount, long timestampMs) {
        this.totalDamage += amount;
        this.hitCount++;
        this.recentSamples.add(new DamageSample(timestampMs, amount));
    }

    /** 이 캐릭터에게 버프를 추가한다. */
    public void applyBuff(ActiveBuff buff) {
        activeBuffs.add(buff);
    }

    /** 특정 버프를 제거한다. 제거 성공하면 true, 없으면 false. */
    public boolean removeBuff(BuffId buffId, ActorId sourceId) {
        return activeBuffs.removeIf(b ->
                b.buffId().equals(buffId) && b.sourceId().equals(sourceId));
    }

    /**
     * 기준 시간(cutoffMs)보다 오래된 데미지 기록을 삭제한다.
     * 매 Tick마다 호출되어 최근 윈도우(기본 15초) 이내의 데이터만 남긴다.
     *
     * 예: cutoffMs가 5000이면, timestampMs가 5000 미만인 기록은 모두 삭제
     */
    public void pruneOldSamples(long cutoffMs) {
        recentSamples.removeIf(s -> s.timestampMs() < cutoffMs);
    }

    /** 최근 윈도우에 남아있는 데미지의 합계를 반환한다. */
    public long recentDamage() {
        long sum = 0;
        for (DamageSample s : recentSamples) {
            sum += s.amount();
        }
        return sum;
    }

    /** 최근 기록 중 가장 오래된 것의 타임스탬프. 기록이 없으면 -1. */
    public long oldestSampleTimestamp() {
        if (recentSamples.isEmpty()) return -1;
        return recentSamples.get(0).timestampMs();
    }

    // --- Getter 메서드들 ---

    public ActorId actorId() { return actorId; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public long totalDamage() { return totalDamage; }
    public int hitCount() { return hitCount; }
    public List<ActiveBuff> activeBuffs() { return Collections.unmodifiableList(activeBuffs); }
    public List<DamageSample> recentSamples() { return Collections.unmodifiableList(recentSamples); }

    /**
     * 슬라이딩 윈도우에 보관되는 데미지 기록 하나.
     * "언제(timestampMs) 얼마나(amount) 데미지를 줬는지"를 저장한다.
     */
    public record DamageSample(long timestampMs, long amount) {
    }
}
