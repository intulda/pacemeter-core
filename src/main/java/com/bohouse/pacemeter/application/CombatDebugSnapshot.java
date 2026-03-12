package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.CombatState;

import java.util.List;

public record CombatDebugSnapshot(
        String fightName,
        CombatState.Phase phase,
        long elapsedMs,
        int territoryId,
        ActorId currentPlayerId,
        ActorDebugEntry currentPlayer,
        List<ActorDebugEntry> actors,
        BossDebugInfo boss,
        EnrageDebugInfo enrage
) {
    public record ActorDebugEntry(
            ActorId actorId,
            String name,
            int jobId,
            boolean currentPlayer,
            long totalDamage,
            long recentDamage,
            double receivedBuffContribution,
            double grantedBuffContribution,
            double onlineRdps,
            int hitCount,
            int observedHitSampleCount,
            int observedCritHitCount,
            int observedDirectHitCount,
            List<ActiveBuffEntry> activeBuffs
    ) {
    }

    public record ActiveBuffEntry(
            int buffId,
            String buffName,
            ActorId sourceId,
            long appliedAtMs,
            long durationMs
    ) {
    }

    public record BossDebugInfo(
            ActorId actorId,
            String name,
            long maxHp
    ) {
    }

    public record EnrageDebugInfo(
            double seconds,
            String confidence,
            String source
    ) {
    }
}
