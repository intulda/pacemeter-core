package com.bohouse.pacemeter.application;

import java.util.List;

public record SubmissionParityReport(
        SubmissionMetadata metadata,
        ReplaySummary replay,
        DamageTextMatchDiagnostics damageTextMatchDiagnostics,
        CombatDebugSnapshot combat,
        FflogsReportSummary fflogs,
        List<ActorParityComparison> comparisons,
        List<UnmatchedLocalActor> unmatchedLocalActors,
        List<UnmatchedFflogsActor> unmatchedFflogsActors
) {
    public record SubmissionMetadata(
            String submissionId,
            String submittedAt,
            String region,
            String clientLanguage,
            int zoneId,
            String encounterName,
            String difficulty,
            List<String> partyJobs,
            String fflogsReportUrl,
            Integer fflogsFightId,
            String pullStartApprox,
            boolean hasDotTicks,
            String notes
    ) {
    }

    public record ReplaySummary(
            String combatLogPath,
            long totalLines,
            long parsedLines,
            long ignoredLines,
            boolean fightStarted,
            long elapsedMs
    ) {
    }

    public record DamageTextMatchDiagnostics(
            long damageTextLines,
            long abilityLines,
            long exactAmountCandidates,
            long exactAmountAndTargetCandidates,
            long exactAmountTargetAndSourceCandidates
    ) {
    }

    public record FflogsReportSummary(
            String status,
            String reportUrl,
            String reportCode,
            Integer selectedFightId,
            String selectedFightName,
            String selectedFightKill,
            Long reportStartTime,
            Long selectedFightDurationMs,
            List<FflogsFightSummary> fights,
            List<FflogsActorSummary> actors,
            String message
    ) {
    }

    public record FflogsFightSummary(
            int id,
            String name,
            long startTime,
            long endTime,
            String kill,
            int encounterId
    ) {
    }

    public record FflogsActorSummary(
            Integer id,
            String name,
            String type,
            String icon,
            double total,
            double activeTime,
            double totalRdps,
            double totalRdpsTaken,
            double totalRdpsGiven,
            double rdpsPerSecond,
            double rdpsTakenPerSecond,
            double rdpsGivenPerSecond
    ) {
    }

    public record ActorParityComparison(
            String matchType,
            String localName,
            String originalName,
            int localJobId,
            long localTotalDamage,
            double localDpsPerSecond,
            double localReceivedBuffContribution,
            double localReceivedBuffPerSecond,
            double localGrantedBuffContribution,
            double localGrantedBuffPerSecond,
            double localExternalDeltaPerSecond,
            double localDerivedRdpsPerSecond,
            double localOnlineRdps,
            Integer fflogsActorId,
            String fflogsName,
            String fflogsType,
            double fflogsTotal,
            double fflogsDpsPerSecond,
            double fflogsTotalRdps,
            double fflogsTotalRdpsTaken,
            double fflogsTotalRdpsGiven,
            double fflogsRdpsPerSecond,
            double fflogsRdpsTakenPerSecond,
            double fflogsRdpsGivenPerSecond,
            double fflogsExternalDeltaPerSecond,
            double receivedDeltaPerSecond,
            double receivedDeltaRatio,
            double grantedDeltaPerSecond,
            double grantedDeltaRatio,
            double externalDeltaPerSecond,
            double externalDeltaRatio,
            double totalDamageDelta,
            double totalDamageDeltaRatio,
            double derivedRdpsDelta,
            double derivedRdpsDeltaRatio,
            double rdpsDelta,
            double rdpsDeltaRatio,
            boolean parityWarning,
            List<String> warningReasons,
            List<SkillBreakdownEntry> localTopSkills,
            List<SkillBreakdownEntry> fflogsTopSkills
    ) {
    }

    public record SkillBreakdownEntry(
            String skillName,
            long totalDamage,
            long hitCount
    ) {
    }

    public record UnmatchedLocalActor(
            String localName,
            int localJobId,
            long localTotalDamage
    ) {
    }

    public record UnmatchedFflogsActor(
            Integer fflogsActorId,
            String fflogsName,
            String fflogsType,
            double fflogsTotal
    ) {
    }
}
