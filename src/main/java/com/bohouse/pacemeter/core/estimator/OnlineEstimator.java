package com.bohouse.pacemeter.core.estimator;

import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.ActorStats;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live rDPS estimator used by the overlay.
 *
 * The estimator blends cumulative attributed DPS with a recent-window estimate.
 * The recent component uses the fixed recent window from {@link CombatState#RECENT_WINDOW_MS}
 * so short burst clusters do not explode the estimate near the end of a fight.
 */
public final class OnlineEstimator {

    private static final double CUMULATIVE_WEIGHT = 0.6;
    private static final double RECENT_WEIGHT = 0.4;
    private static final long RECENT_BLEND_FADE_MS = 180_000;

    private static final long MIN_DURATION_HIGH_MS = 60_000;
    private static final long MIN_DURATION_MED_MS = 30_000;
    private static final int MIN_HIT_COUNT = 10;
    private static final long MIN_WINDOW_DATA_MS = 5_000;
    private static final double VARIANCE_THRESHOLD = 0.30;

    public OnlineEstimator() {}

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

            double cumulativeAttributedDamage = stats.totalDamage()
                    - stats.totalReceivedBuffContribution()
                    + stats.totalGrantedBuffContribution();
            if (cumulativeAttributedDamage <= 0) {
                results.put(actorId, new RdpsEstimate(0.0, Confidence.none()));
                continue;
            }

            double cumulativeDps = cumulativeAttributedDamage / elapsedSec;

            double recentDamage = stats.recentDamage()
                    - stats.recentReceivedBuffContribution()
                    + stats.recentGrantedBuffContribution();
            if (recentDamage < 0) {
                recentDamage = 0;
            }

            long recentWindowMs = Math.min(elapsedMs, CombatState.RECENT_WINDOW_MS);
            double recentDps = recentWindowMs > 0
                    ? recentDamage / (recentWindowMs / 1000.0)
                    : cumulativeDps;

            double recentWeight = blendedRecentWeight(elapsedMs);
            double cumulativeWeight = 1.0 - recentWeight;
            double onlineRdps = (cumulativeWeight * cumulativeDps)
                    + (recentWeight * recentDps);

            Confidence confidence = computeConfidence(
                    elapsedMs,
                    stats,
                    cumulativeDps,
                    recentDps,
                    recentWindowMs
            );

            results.put(actorId, new RdpsEstimate(onlineRdps, confidence));
        }

        return results;
    }

    private Confidence computeConfidence(
            long elapsedMs,
            ActorStats stats,
            double cumulativeDps,
            double recentDps,
            long recentWindowMs
    ) {
        Confidence.Builder builder = new Confidence.Builder();

        if (elapsedMs < MIN_DURATION_MED_MS) {
            builder.penalize(0.4, "Fight < 30s");
        } else if (elapsedMs < MIN_DURATION_HIGH_MS) {
            builder.penalize(0.2, "Fight < 60s");
        }

        if (stats.hitCount() < MIN_HIT_COUNT) {
            builder.penalize(0.3, "Few samples (" + stats.hitCount() + " hits)");
        }

        if (recentWindowMs < MIN_WINDOW_DATA_MS) {
            builder.penalize(0.2, "Short window (" + recentWindowMs + "ms)");
        }

        if (cumulativeDps > 0) {
            double variance = Math.abs(cumulativeDps - recentDps) / cumulativeDps;
            if (variance > VARIANCE_THRESHOLD) {
                builder.penalize(0.15, "High variance (" + String.format("%.0f%%", variance * 100) + ")");
            }
        }

        return builder.build();
    }

    private double blendedRecentWeight(long elapsedMs) {
        if (elapsedMs <= 0) {
            return RECENT_WEIGHT;
        }
        if (elapsedMs >= RECENT_BLEND_FADE_MS) {
            return 0.0;
        }
        double fadeRatio = 1.0 - (elapsedMs / (double) RECENT_BLEND_FADE_MS);
        return RECENT_WEIGHT * fadeRatio;
    }
}
