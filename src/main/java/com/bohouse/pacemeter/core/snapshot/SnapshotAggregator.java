package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.core.estimator.Confidence;
import com.bohouse.pacemeter.core.estimator.OnlineEstimator;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.estimator.RdpsEstimate;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.ActorStats;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
     * @param state    현재 전투 상태
     * @param profile  레퍼런스 페이스 프로필 (없으면 PaceProfile.NONE 사용)
     * @param isFinal  이 스냅샷이 FightEnd에 의한 마지막 스냅샷인지 여부
     * @return 오버레이에서 바로 렌더링 가능한 스냅샷
     */
    public OverlaySnapshot aggregate(CombatState state, PaceProfile profile, boolean isFinal) {
        long elapsedMs = state.elapsedMs();
        double elapsedSec = elapsedMs / 1000.0;
        long totalPartyDamage = state.totalPartyDamage();
        double partyDps = (elapsedSec > 0) ? totalPartyDamage / elapsedSec : 0.0;

        // 모든 캐릭터의 rDPS 추정
        Map<ActorId, RdpsEstimate> estimates = estimator.estimate(state);

        // 캐릭터별 스냅샷 생성
        List<ActorSnapshot> actorSnapshots = new ArrayList<>();
        for (Map.Entry<ActorId, ActorStats> entry : state.actors().entrySet()) {
            ActorId actorId = entry.getKey();
            ActorStats stats = entry.getValue();

            double dps = (elapsedSec > 0) ? stats.totalDamage() / elapsedSec : 0.0;
            double damagePercent = (totalPartyDamage > 0)
                    ? (double) stats.totalDamage() / totalPartyDamage
                    : 0.0;

            RdpsEstimate est = estimates.getOrDefault(actorId,
                    new RdpsEstimate(0.0, Confidence.none()));

            // 슬라이딩 윈도우 기반 최근 DPS 계산
            long recentDamage = stats.recentDamage();
            long oldestSample = stats.oldestSampleTimestamp();
            long windowSpan = (oldestSample >= 0) ? (elapsedMs - oldestSample) : 0;
            double recentDps = (windowSpan > 0) ? recentDamage / (windowSpan / 1000.0) : dps;

            actorSnapshots.add(new ActorSnapshot(
                    actorId,
                    stats.name(),
                    stats.totalDamage(),
                    dps,
                    est.actorOnlineRdps(),
                    est.confidence(),
                    damagePercent,
                    stats.hitCount(),
                    recentDps
            ));
        }

        // 데미지 높은 순으로 정렬 (1등이 맨 위에 오도록)
        actorSnapshots.sort(Comparator.comparingLong(ActorSnapshot::totalDamage).reversed());

        // 페이스 비교 결과 생성
        PaceComparison paceComparison = buildPaceComparison(profile, elapsedMs, totalPartyDamage, partyDps);

        return new OverlaySnapshot(
                state.fightName(),
                state.phase(),
                elapsedMs,
                formatElapsed(elapsedMs),
                totalPartyDamage,
                partyDps,
                List.copyOf(actorSnapshots),
                paceComparison,
                isFinal
        );
    }

    /**
     * 페이스 비교 데이터를 만든다.
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

    /** 밀리초를 "분:초" 형식으로 변환한다. 예: 125000ms → "2:05" */
    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
