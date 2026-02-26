package com.bohouse.pacemeter.core.estimator;

/**
 * FFLogs 그래프 데이터로부터 구성한 타임라인 기반 PaceProfile.
 * 각 시점의 누적 데미지를 저장하고 선형 보간으로 기대값을 제공한다.
 */
public class TimelinePaceProfile implements PaceProfile {

    private final String label;
    private final long totalDurationMs;
    private final long[] timePoints;       // 파이트 기준 경과 ms (오름차순)
    private final long[] cumulativeDamage; // 각 시점의 누적 파티 데미지

    public TimelinePaceProfile(String label, long totalDurationMs,
                               long[] timePoints, long[] cumulativeDamage) {
        if (timePoints.length != cumulativeDamage.length || timePoints.length < 2) {
            throw new IllegalArgumentException("timePoints and cumulativeDamage must have same length >= 2");
        }
        this.label = label;
        this.totalDurationMs = totalDurationMs;
        this.timePoints = timePoints;
        this.cumulativeDamage = cumulativeDamage;
    }

    @Override
    public String label() { return label; }

    @Override
    public long totalDurationMs() { return totalDurationMs; }

    @Override
    public long expectedCumulativeDamage(long elapsedMs) {
        if (elapsedMs <= 0) return 0;

        int n = timePoints.length;

        // 범위 초과: 마지막 구간 기울기로 선형 외삽
        if (elapsedMs >= timePoints[n - 1]) {
            long t0 = timePoints[n - 2], t1 = timePoints[n - 1];
            long d0 = cumulativeDamage[n - 2], d1 = cumulativeDamage[n - 1];
            double rate = (double) (d1 - d0) / (t1 - t0);
            return d1 + (long) (rate * (elapsedMs - t1));
        }

        // 이진 탐색으로 구간 찾기
        int lo = 0, hi = n - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (timePoints[mid] <= elapsedMs) lo = mid;
            else hi = mid;
        }

        // [lo, hi] 구간에서 선형 보간
        long t0 = timePoints[lo], t1 = timePoints[lo + 1];
        long d0 = cumulativeDamage[lo], d1 = cumulativeDamage[lo + 1];
        double ratio = (double) (elapsedMs - t0) / (t1 - t0);
        return d0 + (long) (ratio * (d1 - d0));
    }

    public int pointCount() { return timePoints.length; }
}