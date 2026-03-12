package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;

/**
 * 현재 딜 페이스로 엔레이지 전에 보스를 처치할 수 있는지 계산한 결과.
 */
public record ClearabilityCheck(
        boolean canClear,
        double estimatedKillTimeSeconds,
        double enrageTimeSeconds,
        double marginSeconds,
        double requiredDps,
        EnrageTimeProvider.ConfidenceLevel confidence
) {

    public static ClearabilityCheck calculate(
            long bossMaxHp,
            long totalPartyDamage,
            long elapsedMs,
            EnrageTimeProvider.EnrageInfo enrageInfo
    ) {
        double elapsedSeconds = elapsedMs / 1000.0;
        double currentDps = elapsedSeconds > 0 ? totalPartyDamage / elapsedSeconds : 0.0;
        double estimatedKillTimeSeconds = currentDps > 0
                ? bossMaxHp / currentDps
                : Double.POSITIVE_INFINITY;
        double enrageTimeSeconds = enrageInfo.seconds();
        double marginSeconds = enrageTimeSeconds - estimatedKillTimeSeconds;
        double requiredDps = enrageTimeSeconds > 0 ? bossMaxHp / enrageTimeSeconds : 0.0;

        return new ClearabilityCheck(
                estimatedKillTimeSeconds <= enrageTimeSeconds,
                estimatedKillTimeSeconds,
                enrageTimeSeconds,
                marginSeconds,
                requiredDps,
                classifyConfidence(elapsedMs)
        );
    }

    static EnrageTimeProvider.ConfidenceLevel classifyConfidence(long elapsedMs) {
        if (elapsedMs < 60_000) {
            return EnrageTimeProvider.ConfidenceLevel.LOW;
        }
        if (elapsedMs < 180_000) {
            return EnrageTimeProvider.ConfidenceLevel.MEDIUM;
        }
        return EnrageTimeProvider.ConfidenceLevel.HIGH;
    }
}
