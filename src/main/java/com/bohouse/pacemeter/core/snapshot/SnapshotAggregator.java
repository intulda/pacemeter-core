package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.estimator.Confidence;
import com.bohouse.pacemeter.core.estimator.OnlineEstimator;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.estimator.RdpsEstimate;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.ActorStats;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.*;

/**
 * 현재 전투 상태(CombatState)를 받아서 오버레이용 스냅샷(OverlaySnapshot)을 만든다.
 *
 * 순수 함수처럼 동작한다: 입력(상태 + 추정기 + 프로필)을 받고, 출력(스냅샷)을 반환한다.
 * 외부에 영향을 주거나 I/O를 하지 않는다.
 *
 * 하는 일:
 *   1. 전체 파티 DPS 계산
 *   2. 각 캐릭터별 DPS, rDPS 추정, 데미지 비율 등 계산
 *   3. 캐릭터를 데미지 높은 순으로 정렬
 *   4. 페이스 프로필이 있으면 비교 결과 계산
 *   5. 이 모든 것을 OverlaySnapshot 하나로 묶어서 반환
 */
public final class SnapshotAggregator {

    private final OnlineEstimator estimator;

    public SnapshotAggregator(OnlineEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * 현재 전투 상태로부터 오버레이 스냅샷을 생성한다.
     *
     * @param state              현재 전투 상태
     * @param partyProfile       파티 전체 페이스 프로필 (없으면 PaceProfile.NONE 사용)
     * @param individualProfile  개인 직업별 페이스 프로필 (없으면 PaceProfile.NONE 사용)
     * @param currentPlayerId    현재 플레이어 ID (개인 페이스 비교 대상)
     * @param isFinal            이 스냅샷이 FightEnd에 의한 마지막 스냅샷인지 여부
     * @param jobIdMap           ActorId → JobID 매핑 (직업 정보)
     * @param enrageInfo         엔레이지 정보 (없으면 clearability 계산 안 함)
     * @return 오버레이에서 바로 렌더링 가능한 스냅샷
     */
    public OverlaySnapshot aggregate(CombatState state, PaceProfile partyProfile, PaceProfile individualProfile,
                                     ActorId currentPlayerId, boolean isFinal, Map<ActorId, Integer> jobIdMap,
                                     Optional<EnrageTimeProvider.EnrageInfo> enrageInfo) {
        long elapsedMs = state.elapsedMs();
        double elapsedSec = elapsedMs / 1000.0;
        long totalPartyDamage = state.totalPartyDamage();
        double partyDps = (elapsedSec > 0) ? totalPartyDamage / elapsedSec : 0.0;

        // 모든 캐릭터의 rDPS 추정
        Map<ActorId, RdpsEstimate> estimates = estimator.estimate(state);

        // 펫 데미지를 주인에게 합산 (펫 ID → 펫 데미지)
        Map<ActorId, Long> petDamageByOwner = new HashMap<>();
        for (Map.Entry<ActorId, ActorStats> entry : state.actors().entrySet()) {
            ActorId petId = entry.getKey();
            ActorId ownerId = state.ownerMap().get(petId);

            if (ownerId != null) {
                // 펫인 경우: 주인의 데미지에 합산
                long petDamage = entry.getValue().totalDamage();
                petDamageByOwner.merge(ownerId, petDamage, Long::sum);
            }
        }

        // 캐릭터별 스냅샷 생성
        List<ActorSnapshot> actorSnapshots = new ArrayList<>();
        for (Map.Entry<ActorId, ActorStats> entry : state.actors().entrySet()) {
            ActorId actorId = entry.getKey();
            ActorStats stats = entry.getValue();

            // 펫 필터링: 주인이 있는 경우 제외
            if (state.ownerMap().containsKey(actorId)) {
                continue;
            }

            // 빈 액터 필터링: 이름이 비어있는 경우만 제외 (0 데미지라도 이름이 있으면 파티원으로 표시)
            if (stats.name() == null || stats.name().isBlank()) {
                continue;
            }

            // 펫 데미지 합산
            long totalDamageWithPet = stats.totalDamage() + petDamageByOwner.getOrDefault(actorId, 0L);

            double dps = (elapsedSec > 0) ? totalDamageWithPet / elapsedSec : 0.0;
            double damagePercent = (totalPartyDamage > 0)
                    ? (double) totalDamageWithPet / totalPartyDamage
                    : 0.0;

            RdpsEstimate est = estimates.getOrDefault(actorId,
                    new RdpsEstimate(0.0, Confidence.none()));

            // 슬라이딩 윈도우 기반 최근 DPS 계산
            long recentDamage = stats.recentDamage();
            long oldestSample = stats.oldestSampleTimestamp();
            long windowSpan = (oldestSample >= 0) ? (elapsedMs - oldestSample) : 0;
            double recentDps = (windowSpan > 0) ? recentDamage / (windowSpan / 1000.0) : dps;

            int jobId = jobIdMap.getOrDefault(actorId, 0);  // 직업 정보 (없으면 0)

            // 개인 페이스 비교: 현재 플레이어인 경우에만 계산
            boolean isCurrentPlayer = actorId.equals(currentPlayerId);
            PaceComparison individualPace = null;
            if (isCurrentPlayer && individualProfile != PaceProfile.NONE) {
                individualPace = buildIndividualPaceComparison(individualProfile, elapsedMs, totalDamageWithPet, dps);
            }

            actorSnapshots.add(new ActorSnapshot(
                    actorId,
                    stats.name(),
                    jobId,
                    totalDamageWithPet,
                    dps,
                    est.actorOnlineRdps(),
                    est.confidence(),
                    damagePercent,
                    stats.hitCount(),
                    recentDps,
                    isCurrentPlayer,
                    individualPace,
                    stats.isDead()
            ));
        }

        // 데미지 높은 순으로 정렬 (1등이 맨 위에 오도록)
        actorSnapshots.sort(Comparator.comparingLong(ActorSnapshot::totalDamage).reversed());

        // 파티 페이스 비교 결과 생성
        PaceComparison partyPace = buildPaceComparison(partyProfile, elapsedMs, totalPartyDamage, partyDps);
        ClearabilityCheck clearability = buildClearability(state, totalPartyDamage, elapsedMs, enrageInfo);

        return new OverlaySnapshot(
                state.fightName(),
                state.phase(),
                elapsedMs,
                formatElapsed(elapsedMs),
                totalPartyDamage,
                partyDps,
                List.copyOf(actorSnapshots),
                partyPace,
                clearability,
                isFinal
        );
    }

    private ClearabilityCheck buildClearability(
            CombatState state,
            long totalPartyDamage,
            long elapsedMs,
            Optional<EnrageTimeProvider.EnrageInfo> enrageInfo
    ) {
        if (enrageInfo.isEmpty() || state.bossInfo().isEmpty()) {
            return null;
        }

        CombatState.BossInfo bossInfo = state.bossInfo().orElseThrow();
        return ClearabilityCheck.calculate(
                bossInfo.maxHp(),
                totalPartyDamage,
                elapsedMs,
                enrageInfo.orElseThrow()
        );
    }

    /**
     * 파티 페이스 비교 데이터를 만든다.
     * 프로필이 없으면(NONE이면) null을 반환한다.
     */
    private PaceComparison buildPaceComparison(PaceProfile profile, long elapsedMs,
                                                long actualDamage, double currentPartyDps) {
        if (profile == PaceProfile.NONE || profile.totalDurationMs() <= 0) {
            return null;
        }

        long expectedDamage = profile.expectedCumulativeDamage(elapsedMs);
        long delta = actualDamage - expectedDamage;
        double deltaPercent = (expectedDamage > 0) ? (delta * 100.0 / expectedDamage) : 0.0;

        // 예상 클리어 시간 계산: 현재 DPS가 유지된다고 가정하고,
        // 레퍼런스 프로필의 총 기대 데미지에 도달하려면 얼마나 걸리는지 계산
        long totalExpectedDamage = profile.expectedCumulativeDamage(profile.totalDurationMs());
        long projectedKillTimeMs = 0;
        if (currentPartyDps > 0 && totalExpectedDamage > 0) {
            // 남은 데미지 / 현재 DPS = 남은 시간
            long remainingDamage = totalExpectedDamage - actualDamage;
            if (remainingDamage > 0) {
                projectedKillTimeMs = elapsedMs + (long) (remainingDamage / currentPartyDps * 1000.0);
            } else {
                // 이미 기대 총 데미지를 초과함 → 지금 당장 클리어 가능
                projectedKillTimeMs = elapsedMs;
            }
        }

        return new PaceComparison(
                profile.label(),
                expectedDamage,
                actualDamage,
                delta,
                deltaPercent,
                projectedKillTimeMs,
                profile.totalDurationMs()
        );
    }

    /**
     * 개인 페이스 비교 데이터를 만든다.
     * 프로필이 없으면(NONE이면) null을 반환한다.
     */
    private PaceComparison buildIndividualPaceComparison(PaceProfile profile, long elapsedMs,
                                                         long actualDamage, double currentDps) {
        if (profile == PaceProfile.NONE || profile.totalDurationMs() <= 0) {
            return null;
        }

        long expectedDamage = profile.expectedCumulativeDamage(elapsedMs);
        long delta = actualDamage - expectedDamage;
        double deltaPercent = (expectedDamage > 0) ? (delta * 100.0 / expectedDamage) : 0.0;

        // 예상 클리어 시간 계산: 현재 DPS가 유지된다고 가정하고,
        // 레퍼런스 프로필의 총 기대 데미지에 도달하려면 얼마나 걸리는지 계산
        long totalExpectedDamage = profile.expectedCumulativeDamage(profile.totalDurationMs());
        long projectedKillTimeMs = 0;
        if (currentDps > 0 && totalExpectedDamage > 0) {
            // 남은 데미지 / 현재 DPS = 남은 시간
            long remainingDamage = totalExpectedDamage - actualDamage;
            if (remainingDamage > 0) {
                projectedKillTimeMs = elapsedMs + (long) (remainingDamage / currentDps * 1000.0);
            } else {
                // 이미 기대 총 데미지를 초과함 → 지금 당장 클리어 가능
                projectedKillTimeMs = elapsedMs;
            }
        }

        return new PaceComparison(
                profile.label(),
                expectedDamage,
                actualDamage,
                delta,
                deltaPercent,
                projectedKillTimeMs,
                profile.totalDurationMs()
        );
    }

    /** 밀리초를 "분:초" 형식으로 변환한다. 예: 125000ms → "2:05" */
    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
