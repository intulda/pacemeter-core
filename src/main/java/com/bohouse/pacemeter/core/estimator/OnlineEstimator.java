package com.bohouse.pacemeter.core.estimator;

import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.ActorStats;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 온라인 rDPS 추정기.
 *
 * "rDPS"란 FF14에서 버프 기여까지 포함한 개인 DPS를 말하는데,
 * 여기서는 정확한 rDPS가 아니라 실시간 근사치를 계산한다.
 *
 * 계산 방법 (가중 평균):
 *   온라인rDPS = 0.6 * 누적DPS + 0.4 * 최근윈도우DPS
 *
 *   - 누적DPS: 전투 시작부터 지금까지의 총 데미지 / 경과 시간
 *   - 최근윈도우DPS: 최근 15초 동안의 데미지 / 15초
 *   -> 두 값을 6:4로 섞어서 안정성과 반응성을 모두 확보한다.
 *
 * 신뢰도 감점 규칙 (최대 1.0에서 시작):
 *   1. 전투 시간 30초 미만 → -0.40 ("데이터가 너무 적어요")
 *   2. 전투 시간 60초 미만 → -0.20 ("데이터가 아직 부족해요")
 *   3. 타격 횟수 10회 미만 → -0.30 ("샘플이 너무 적어요")
 *   4. 최근 윈도우 데이터 5초 미만 → -0.20 ("최근 데이터가 부족해요")
 *   5. 누적DPS와 최근DPS 차이 30% 초과 → -0.15 ("DPS 변동이 심해요")
 *
 * 전제 사항:
 *   - 이것은 FF Logs rDPS가 아니다. 페이스 비교용 근사값이다.
 *   - 버프 기여도 계산은 P1에서 추가 예정이다.
 *   - 소환수 데미지는 현재 소환수 본인에게 귀속된다. 주인 합산은 P1에서 처리.
 */
public final class OnlineEstimator {

    // --- 조정 가능한 파라미터 (P0 기본값) ---

    /** 누적DPS의 가중치 (블렌딩에서 차지하는 비율) */
    private static final double CUMULATIVE_WEIGHT = 0.6;

    /** 최근윈도우DPS의 가중치 */
    private static final double RECENT_WEIGHT = 0.4;

    /** 높은 신뢰도에 필요한 최소 전투 시간 (밀리초) */
    private static final long MIN_DURATION_HIGH_MS = 60_000;
    private static final long MIN_DURATION_MED_MS = 30_000;

    /** 합리적인 신뢰도에 필요한 최소 타격 횟수 */
    private static final int MIN_HIT_COUNT = 10;

    /** 최근 윈도우에 유의미한 데이터가 있으려면 필요한 최소 시간 (밀리초) */
    private static final long MIN_WINDOW_DATA_MS = 5_000;

    /** 누적DPS와 최근DPS 차이가 이 비율을 넘으면 "변동이 심하다"고 판단 */
    private static final double VARIANCE_THRESHOLD = 0.30;

    // P0에서는 인스턴스 상태가 필요 없다. 모든 입력은 CombatState에서 가져온다.
    public OnlineEstimator() {}

    /**
     * 현재 전투 상태에서 모든 캐릭터의 온라인 rDPS를 추정한다.
     *
     * @param state 현재 전투 상태 (ACTIVE 또는 ENDED 상태여야 함)
     * @return 캐릭터ID → rDPS추정치 맵. 전투 중이 아니면 빈 맵 반환
     */
    public Map<ActorId, RdpsEstimate> estimate(CombatState state) {
        Map<ActorId, RdpsEstimate> results = new LinkedHashMap<>();

        if (state.phase() == CombatState.Phase.IDLE) {
            return results;
        }

        long elapsedMs = state.elapsedMs();
        if (elapsedMs <= 0) {
            return results;
        }

        double elapsedSec = elapsedMs / 1000.0;

        for (Map.Entry<ActorId, ActorStats> entry : state.actors().entrySet()) {
            ActorId actorId = entry.getKey();
            ActorStats stats = entry.getValue();

            if (stats.totalDamage() <= 0) {
                results.put(actorId, new RdpsEstimate(0.0, Confidence.none()));
                continue;
            }

            // --- DPS 계산 ---
            double cumulativeDps = stats.totalDamage() / elapsedSec;

            // 최근 윈도우 DPS: 윈도우 상수가 아니라 실제 데이터 범위를 사용
            long recentDamage = stats.recentDamage();
            long oldestSample = stats.oldestSampleTimestamp();
            long windowSpanMs = (oldestSample >= 0) ? (elapsedMs - oldestSample) : 0;
            double recentDps;
            if (windowSpanMs > 0 && recentDamage > 0) {
                recentDps = recentDamage / (windowSpanMs / 1000.0);
            } else {
                // 최근 데이터가 없으면 누적DPS로 대체
                recentDps = cumulativeDps;
            }

            // --- 블렌딩 (가중 평균) ---
            double onlineRdps = (CUMULATIVE_WEIGHT * cumulativeDps)
                    + (RECENT_WEIGHT * recentDps);

            // --- 신뢰도 계산 ---
            Confidence confidence = computeConfidence(elapsedMs, stats, cumulativeDps, recentDps, windowSpanMs);

            results.put(actorId, new RdpsEstimate(onlineRdps, confidence));
        }

        return results;
    }

    /** 각종 조건을 검사해서 신뢰도 점수를 계산한다 */
    private Confidence computeConfidence(
            long elapsedMs,
            ActorStats stats,
            double cumulativeDps,
            double recentDps,
            long windowSpanMs
    ) {
        Confidence.Builder builder = new Confidence.Builder();

        // 규칙 1, 2: 전투 시간 검사
        if (elapsedMs < MIN_DURATION_MED_MS) {
            builder.penalize(0.4, "Fight < 30s");
        } else if (elapsedMs < MIN_DURATION_HIGH_MS) {
            builder.penalize(0.2, "Fight < 60s");
        }

        // 규칙 3: 타격 횟수 검사
        if (stats.hitCount() < MIN_HIT_COUNT) {
            builder.penalize(0.3, "Few samples (" + stats.hitCount() + " hits)");
        }

        // 규칙 4: 최근 윈도우 데이터 검사
        if (windowSpanMs < MIN_WINDOW_DATA_MS) {
            builder.penalize(0.2, "Short window (" + windowSpanMs + "ms)");
        }

        // 규칙 5: 누적DPS와 최근DPS 간 변동성 검사
        if (cumulativeDps > 0) {
            double variance = Math.abs(cumulativeDps - recentDps) / cumulativeDps;
            if (variance > VARIANCE_THRESHOLD) {
                builder.penalize(0.15, "High variance (" + String.format("%.0f%%", variance * 100) + ")");
            }
        }

        return builder.build();
    }
}
