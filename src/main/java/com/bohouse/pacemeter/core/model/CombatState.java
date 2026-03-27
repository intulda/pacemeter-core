package com.bohouse.pacemeter.core.model;

import com.bohouse.pacemeter.core.event.CombatEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    private static final double DEFAULT_CRIT_RATE = 0.25;
    private static final double DEFAULT_DIRECT_HIT_RATE = 0.15;
    private static final double MIN_CRIT_RATE = 0.05;
    private static final double CRIT_DAMAGE_BASE = 1.4;
    private static final double DIRECT_HIT_DAMAGE_MULTIPLIER = 1.25;

    /** 전투 진행 단계. IDLE=대기, ACTIVE=전투중, ENDED=전투종료 */
    public enum Phase { IDLE, ACTIVE, ENDED }

    private Phase phase;
    private String fightName;
    private long fightStartMs;     // 전투 시작 시각 (어댑터용, 코어에서는 직접 사용하지 않음)
    private long elapsedMs;        // 전투 시작 이후 경과 시간 (밀리초)
    private long totalPartyDamage;
    private BossInfo bossInfo;

    /** 캐릭터별 통계. 먼저 등장한 순서대로 저장된다. */
    private final Map<ActorId, ActorStats> actors;

    /** 펫/소환수의 주인 매핑 (펫 ID → 주인 ID) */
    private final Map<ActorId, ActorId> ownerMap;
    private final Map<DotKey, DotSnapshot> dotSnapshots;

    /** 최근 DPS 계산에 사용할 슬라이딩 윈도우 크기 (밀리초). 기본 15초. */
    public static final long RECENT_WINDOW_MS = 15_000;

    public CombatState() {
        this.phase = Phase.IDLE;
        this.fightName = "";
        this.fightStartMs = 0;
        this.elapsedMs = 0;
        this.totalPartyDamage = 0;
        this.bossInfo = null;
        this.actors = new LinkedHashMap<>();
        this.ownerMap = new LinkedHashMap<>();
        this.dotSnapshots = new LinkedHashMap<>();
    }

    public record BossInfo(ActorId actorId, String name, long maxHp) {}

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
        } else if (event instanceof CombatEvent.ActorJoined e) {
            return reduceActorJoined(e);
        } else if (event instanceof CombatEvent.BossIdentified e) {
            return reduceBossIdentified(e);
        } else if (event instanceof CombatEvent.DamageEvent e) {
            return reduceDamage(e);
        } else if (event instanceof CombatEvent.BuffApply e) {
            return reduceBuffApply(e);
        } else if (event instanceof CombatEvent.BuffRemove e) {
            return reduceBuffRemove(e);
        } else if (event instanceof CombatEvent.ActorDeath e) {
            return reduceActorDeath(e);
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
        this.bossInfo = null;
        this.actors.clear();
        this.dotSnapshots.clear();
        return false;
    }

    /** 파티원 참여: actors 맵에 등록 (아직 데미지를 주지 않았어도 스냅샷에 포함되도록) */
    private boolean reduceActorJoined(CombatEvent.ActorJoined e) {
        this.elapsedMs = e.timestampMs();
        actors.computeIfAbsent(e.actorId(), id -> new ActorStats(id, e.actorName()));
        return false;  // 스냅샷 생성 안 함 (Tick에서만 생성)
    }

    private boolean reduceBossIdentified(CombatEvent.BossIdentified e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        this.bossInfo = new BossInfo(e.actorId(), e.actorName(), e.maxHp());
        return false;
    }

    /** 데미지 이벤트: 해당 캐릭터의 데미지를 누적 */
    private boolean reduceDamage(CombatEvent.DamageEvent e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        pruneExpiredState(e.timestampMs());

        // 처음 보는 캐릭터면 새로 등록, 기존 캐릭터면 기존 통계를 가져옴
        ActorStats stats = actors.computeIfAbsent(e.sourceId(),
                id -> new ActorStats(id, e.sourceName()));

        // 이름이 비어있었는데 이번에 이름이 들어왔으면 업데이트
        if (stats.name().isEmpty() && !e.sourceName().isEmpty()) {
            stats.setName(e.sourceName());
        }

        // 사망 중인 캐릭터가 데미지를 주면 부활로 간주
        if (stats.isDead()) {
            stats.markResurrected();
        }

        if (e.damageType() != DamageType.DOT) {
            stats.observeHitOutcome(e.criticalHit(), e.directHit());
        }

        stats.addDamage(
                e.amount(),
                e.timestampMs(),
                e.actionId(),
                e.actionName(),
                e.damageType() != DamageType.DOT
        );
        AttributionContext attributionContext = resolveAttributionContext(e);
        attributeExternalBuffContribution(stats, e, e.timestampMs(), attributionContext);
        this.totalPartyDamage += e.amount();

        return false;
    }

    private void attributeExternalBuffContribution(
            ActorStats sourceStats,
            CombatEvent.DamageEvent event,
            long timestampMs,
            AttributionContext attributionContext
    ) {
        long amount = event.amount();
        double directDamageExtra = 0.0;
        if (attributionContext.totalMultiplier() > 1.0 && !attributionContext.providerMultiplierLogWeight().isEmpty()) {
            directDamageExtra = amount - (amount / attributionContext.totalMultiplier());
        }

        double adjustedAmount = amount - directDamageExtra;
        double critPortion = calculateCritPortion(event, adjustedAmount, attributionContext);
        double directHitPortion = calculateDirectHitPortion(event, adjustedAmount, attributionContext);

        double totalCritRateUp = attributionContext.totalCritRateUp();
        double totalDirectHitRateUp = attributionContext.totalDirectHitRateUp();
        double critRateExtra = attributionContext.buffedCritRate() > 0.0
                ? critPortion * (totalCritRateUp / attributionContext.buffedCritRate())
                : 0.0;
        double directHitRateExtra = attributionContext.buffedDirectHitRate() > 0.0
                ? directHitPortion * (totalDirectHitRateUp / attributionContext.buffedDirectHitRate())
                : 0.0;
        double totalExtra = directDamageExtra + critRateExtra + directHitRateExtra;
        if (totalExtra <= 0.0) {
            return;
        }

        sourceStats.addReceivedBuffContribution(totalExtra, timestampMs);

        if (directDamageExtra > 0.0) {
            double totalWeight = Math.log(attributionContext.totalMultiplier());
            if (totalWeight > 0.0) {
                for (Map.Entry<ActorId, Double> entry : attributionContext.providerMultiplierLogWeight().entrySet()) {
                    ActorStats providerStats = actors.computeIfAbsent(entry.getKey(), id -> new ActorStats(id, ""));
                    double providerExtra = directDamageExtra * (entry.getValue() / totalWeight);
                    providerStats.addGrantedBuffContribution(providerExtra, timestampMs);
                }
            }
        }

        if (critRateExtra > 0.0 && totalCritRateUp > 0.0) {
            for (Map.Entry<ActorId, Double> entry : attributionContext.providerCritRate().entrySet()) {
                ActorStats providerStats = actors.computeIfAbsent(entry.getKey(), id -> new ActorStats(id, ""));
                double providerExtra = critPortion * (entry.getValue() / attributionContext.buffedCritRate());
                providerStats.addGrantedBuffContribution(providerExtra, timestampMs);
            }
        }

        if (directHitRateExtra > 0.0 && totalDirectHitRateUp > 0.0) {
            for (Map.Entry<ActorId, Double> entry : attributionContext.providerDirectHitRate().entrySet()) {
                ActorStats providerStats = actors.computeIfAbsent(entry.getKey(), id -> new ActorStats(id, ""));
                double providerExtra = directHitPortion * (entry.getValue() / attributionContext.buffedDirectHitRate());
                providerStats.addGrantedBuffContribution(providerExtra, timestampMs);
            }
        }
    }

    private double calculateCritPortion(
            CombatEvent.DamageEvent event,
            double adjustedAmount,
            AttributionContext attributionContext
    ) {
        if (attributionContext.totalCritRateUp() <= 0.0) {
            return 0.0;
        }

        double critMultiplier = CRIT_DAMAGE_BASE + (attributionContext.unbuffedCritRate() - MIN_CRIT_RATE);
        if (event.damageType() == DamageType.DOT) {
            double buffedDotAmount = adjustedAmount * (1.0 + (critMultiplier - 1.0) * attributionContext.buffedCritRate());
            double unbuffedDotAmount = adjustedAmount * (1.0 + (critMultiplier - 1.0) * attributionContext.unbuffedCritRate());
            return Math.max(0.0, buffedDotAmount - unbuffedDotAmount);
        }
        if (!event.criticalHit()) {
            return 0.0;
        }

        return extractMultiplicativePortion(adjustedAmount, critMultiplier);
    }

    private double calculateDirectHitPortion(
            CombatEvent.DamageEvent event,
            double adjustedAmount,
            AttributionContext attributionContext
    ) {
        if (attributionContext.totalDirectHitRateUp() <= 0.0) {
            return 0.0;
        }

        if (event.damageType() == DamageType.DOT) {
            double buffedDotAmount = adjustedAmount * (1.0 + (DIRECT_HIT_DAMAGE_MULTIPLIER - 1.0) * attributionContext.buffedDirectHitRate());
            double unbuffedDotAmount = adjustedAmount * (1.0 + (DIRECT_HIT_DAMAGE_MULTIPLIER - 1.0) * attributionContext.unbuffedDirectHitRate());
            return Math.max(0.0, buffedDotAmount - unbuffedDotAmount);
        }
        if (!event.directHit()) {
            return 0.0;
        }

        return extractMultiplicativePortion(adjustedAmount, DIRECT_HIT_DAMAGE_MULTIPLIER);
    }

    private double extractMultiplicativePortion(double adjustedAmount, double multiplier) {
        if (multiplier <= 1.0) {
            return 0.0;
        }
        return adjustedAmount - (adjustedAmount / multiplier);
    }

    private AttributionContext resolveAttributionContext(CombatEvent.DamageEvent event) {
        if (event.damageType() != DamageType.DOT) {
            return captureCurrentAttributionContext(event.sourceId(), event.targetId());
        }

        DotSnapshot dotSnapshot = null;
        if (event.actionId() > 0) {
            dotSnapshot = dotSnapshots.get(new DotKey(event.sourceId(), event.targetId(), new BuffId(event.actionId())));
            if (dotSnapshot == null) {
                for (Integer statusId : DotAttributionCatalog.statusIdsForAction(event.actionId())) {
                    dotSnapshot = dotSnapshots.get(new DotKey(event.sourceId(), event.targetId(), new BuffId(statusId)));
                    if (dotSnapshot != null) {
                        break;
                    }
                }
            }
        }
        if (dotSnapshot == null) {
            dotSnapshot = findFallbackDotSnapshot(event.sourceId(), event.targetId());
        }
        if (dotSnapshot != null) {
            return dotSnapshot.attributionContext();
        }
        return captureCurrentAttributionContext(event.sourceId(), event.targetId());
    }

    private DotSnapshot findFallbackDotSnapshot(ActorId sourceId, ActorId targetId) {
        DotSnapshot fallback = null;
        for (Map.Entry<DotKey, DotSnapshot> entry : dotSnapshots.entrySet()) {
            DotKey key = entry.getKey();
            if (!key.sourceId().equals(sourceId) || !key.targetId().equals(targetId)) {
                continue;
            }
            if (fallback == null || entry.getValue().appliedAtMs() > fallback.appliedAtMs()) {
                fallback = entry.getValue();
            }
        }
        return fallback;
    }

    private AttributionContext captureCurrentAttributionContext(ActorId sourceId, ActorId targetId) {
        MultiplierHolder totalMultiplier = new MultiplierHolder(1.0);
        Map<ActorId, Double> providerMultiplierLogWeight = new LinkedHashMap<>();
        Map<ActorId, Double> providerCritRate = new LinkedHashMap<>();
        Map<ActorId, Double> providerDirectHitRate = new LinkedHashMap<>();

        ActorStats sourceStats = actors.get(sourceId);
        if (sourceStats != null) {
            for (ActiveBuff buff : sourceStats.activeBuffs()) {
                if (sharesOwnershipGroup(buff.sourceId(), sourceId)) {
                    continue;
                }
                applyBuffEffects(buff, totalMultiplier, providerMultiplierLogWeight, providerCritRate, providerDirectHitRate);
            }
        }

        ActorStats targetStats = actors.get(targetId);
        if (targetStats != null) {
            for (ActiveBuff buff : targetStats.activeBuffs()) {
                if (sharesOwnershipGroup(buff.sourceId(), sourceId)) {
                    continue;
                }
                applyBuffEffects(buff, totalMultiplier, providerMultiplierLogWeight, providerCritRate, providerDirectHitRate);
            }
        }

        double totalCritRateUp = providerCritRate.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalDirectHitRateUp = providerDirectHitRate.values().stream().mapToDouble(Double::doubleValue).sum();
        double unbuffedCritRate = estimateUnbuffedRate(
                sourceStats,
                totalCritRateUp,
                DEFAULT_CRIT_RATE,
                MIN_CRIT_RATE,
                RateKind.CRIT
        );
        double unbuffedDirectHitRate = estimateUnbuffedRate(
                sourceStats,
                totalDirectHitRateUp,
                DEFAULT_DIRECT_HIT_RATE,
                0.0,
                RateKind.DIRECT_HIT
        );
        double buffedCritRate = clampRate(unbuffedCritRate + totalCritRateUp);
        double buffedDirectHitRate = clampRate(unbuffedDirectHitRate + totalDirectHitRateUp);

        return new AttributionContext(
                totalMultiplier.value(),
                Map.copyOf(providerMultiplierLogWeight),
                Map.copyOf(providerCritRate),
                Map.copyOf(providerDirectHitRate),
                totalCritRateUp,
                totalDirectHitRateUp,
                unbuffedCritRate,
                buffedCritRate,
                unbuffedDirectHitRate,
                buffedDirectHitRate
        );
    }

    private double estimateUnbuffedRate(
            ActorStats sourceStats,
            double totalRateUp,
            double defaultRate,
            double minimumRate,
            RateKind rateKind
    ) {
        // CombatState only keeps one rolling observed crit/direct rate per actor.
        // Subtracting the *current* buff stack from that fight-wide sample biases the base rate
        // downward during stacked buff windows and consistently over-attributes external rDPS.
        // Use a stable baseline instead of deriving a per-hit "unbuffed" rate from mixed samples.
        return Math.max(minimumRate, defaultRate);
    }

    private double clampRate(double rate) {
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private boolean sharesOwnershipGroup(ActorId left, ActorId right) {
        return resolveRootOwner(left).equals(resolveRootOwner(right));
    }

    private ActorId resolveRootOwner(ActorId actorId) {
        ActorId current = actorId;
        while (current != null) {
            ActorId owner = ownerMap.get(current);
            if (owner == null || owner.equals(current)) {
                return current;
            }
            current = owner;
        }
        return actorId;
    }

    private void applyBuffEffects(
            ActiveBuff buff,
            MultiplierHolder totalMultiplier,
            Map<ActorId, Double> providerMultiplierLogWeight,
            Map<ActorId, Double> providerCritRate,
            Map<ActorId, Double> providerDirectHitRate
    ) {
        Optional<RaidBuffLibrary.RaidBuffDefinition> maybeDefinition = RaidBuffLibrary.find(buff.buffId(), buff.buffName());
        if (maybeDefinition.isEmpty()) {
            return;
        }

        for (RaidBuffLibrary.RaidBuffEffect effect : maybeDefinition.orElseThrow().effects()) {
            switch (effect.kind()) {
                case PERCENT_DAMAGE -> {
                    double multiplier = 1.0 + effect.amount();
                    totalMultiplier.multiply(multiplier);
                    providerMultiplierLogWeight.merge(buff.sourceId(), Math.log(multiplier), Double::sum);
                }
                case CRIT_RATE -> providerCritRate.merge(buff.sourceId(), effect.amount(), Double::sum);
                case DIRECT_HIT_RATE -> providerDirectHitRate.merge(buff.sourceId(), effect.amount(), Double::sum);
            }
        }
    }

    private static final class MultiplierHolder {
        private double value;

        private MultiplierHolder(double value) {
            this.value = value;
        }

        private void multiply(double multiplier) {
            this.value *= multiplier;
        }

        private double value() {
            return value;
        }
    }

    private record AttributionContext(
            double totalMultiplier,
            Map<ActorId, Double> providerMultiplierLogWeight,
            Map<ActorId, Double> providerCritRate,
            Map<ActorId, Double> providerDirectHitRate,
            double totalCritRateUp,
            double totalDirectHitRateUp,
            double unbuffedCritRate,
            double buffedCritRate,
            double unbuffedDirectHitRate,
            double buffedDirectHitRate
    ) {
    }

    private enum RateKind {
        CRIT,
        DIRECT_HIT
    }

    private record DotKey(ActorId sourceId, ActorId targetId, BuffId buffId) {
    }

    private record DotSnapshot(
            BuffId buffId,
            String buffName,
            AttributionContext attributionContext,
            long appliedAtMs,
            long durationMs
    ) {
    }

    /** 버프 적용: 대상 캐릭터의 버프 목록에 추가 */
    private boolean reduceBuffApply(CombatEvent.BuffApply e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        pruneExpiredState(e.timestampMs());

        // 버프는 "받는 쪽(target)" 캐릭터에 기록한다
        ActorStats target = actors.computeIfAbsent(e.targetId(),
                id -> new ActorStats(id, ""));

        if (DotStatusLibrary.isLikelyDot(e.buffId(), e.buffName(), e.durationMs(), e.sourceId(), e.targetId())) {
            dotSnapshots.put(
                    new DotKey(e.sourceId(), e.targetId(), e.buffId()),
                    new DotSnapshot(
                            e.buffId(),
                            e.buffName(),
                            captureCurrentAttributionContext(e.sourceId(), e.targetId()),
                            e.timestampMs(),
                            e.durationMs()
                    )
            );
        }

        target.applyBuff(new ActiveBuff(e.buffId(), e.buffName(), e.sourceId(), e.timestampMs(), e.durationMs()));

        return false;
    }

    /** 버프 제거: 대상 캐릭터의 버프 목록에서 삭제 */
    private boolean reduceBuffRemove(CombatEvent.BuffRemove e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        pruneExpiredState(e.timestampMs());

        ActorStats target = actors.get(e.targetId());
        if (target != null) {
            target.removeBuff(e.buffId(), e.sourceId());
        }

        DotKey dotKey = new DotKey(e.sourceId(), e.targetId(), e.buffId());
        DotSnapshot dotSnapshot = dotSnapshots.get(dotKey);
        if (dotSnapshot != null && dotSnapshot.buffId().equals(e.buffId())) {
            dotSnapshots.remove(dotKey);
        }

        return false;
    }

    /**
     * 캐릭터 사망: 해당 캐릭터를 사망 상태로 표시.
     * EncDPS 방식이므로 사망 후에도 전투 시간은 계속 흐르며,
     * 사망 중에는 데미지를 못 주어 자동으로 DPS가 하락한다.
     */
    private boolean reduceActorDeath(CombatEvent.ActorDeath e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();

        ActorStats actor = actors.get(e.actorId());
        if (actor != null) {
            actor.markDead(e.timestampMs());
        } else {
            // 처음 보는 캐릭터면 등록 후 사망 상태로 설정
            ActorStats newActor = new ActorStats(e.actorId(), e.actorName());
            newActor.markDead(e.timestampMs());
            actors.put(e.actorId(), newActor);
        }

        return false;  // 스냅샷 생성 안 함 (Tick에서만 생성)
    }

    /**
     * 틱 이벤트: 각 캐릭터의 오래된 데미지 기록을 정리하고, 스냅샷 생성을 요청한다.
     * 약 250ms마다 호출되어 오버레이 화면을 갱신하는 트리거 역할.
     */
    private boolean reduceTick(CombatEvent.Tick e) {
        if (phase != Phase.ACTIVE) return false;

        this.elapsedMs = e.timestampMs();
        pruneExpiredState(e.timestampMs());

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
        pruneExpiredState(e.timestampMs());
        this.phase = Phase.ENDED;

        return true;  // 마지막 스냅샷을 만들어라!
    }

    private void pruneExpiredState(long currentTimestampMs) {
        for (ActorStats stats : actors.values()) {
            stats.pruneExpiredBuffs(currentTimestampMs);
        }
        dotSnapshots.entrySet().removeIf(entry -> {
            DotSnapshot snapshot = entry.getValue();
            return snapshot.durationMs() > 0
                    && snapshot.appliedAtMs() + snapshot.durationMs() <= currentTimestampMs;
        });
    }

    // ========================================================================
    // 읽기 전용 접근자 (Aggregator, Estimator 등에서 사용)
    // ========================================================================

    public Phase phase() { return phase; }
    public String fightName() { return fightName; }
    public long elapsedMs() { return elapsedMs; }
    public long totalPartyDamage() { return totalPartyDamage; }
    public Optional<BossInfo> bossInfo() { return Optional.ofNullable(bossInfo); }

    /** 등록된 모든 캐릭터의 통계를 반환 (수정 불가) */
    public Map<ActorId, ActorStats> actors() {
        return Collections.unmodifiableMap(actors);
    }

    /** 특정 캐릭터의 통계를 반환. 없으면 null. */
    public ActorStats getActor(ActorId id) {
        return actors.get(id);
    }

    /** 펫/소환수의 주인 매핑을 반환 (수정 불가) */
    public Map<ActorId, ActorId> ownerMap() {
        return Collections.unmodifiableMap(ownerMap);
    }

    /** 펫/소환수의 주인을 설정한다. ActIngestionService에서 호출. */
    public void setOwner(ActorId petId, ActorId ownerId) {
        if (ownerId != null && ownerId.value() != 0) {
            ownerMap.put(petId, ownerId);
        }
    }

    /** 전투 경계가 바뀔 때 펫/소환수 주인 매핑을 초기화한다. */
    public void clearOwners() {
        ownerMap.clear();
    }
}
