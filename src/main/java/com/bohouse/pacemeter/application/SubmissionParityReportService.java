package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.DamageText;
import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.NetworkAbilityRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SubmissionParityReportService {

    private final ActLineParser parser;
    private final ObjectMapper objectMapper;
    private final FflogsZoneLookup fflogsZoneLookup;
    private final EnrageTimeProvider enrageTimeProvider;
    private final FflogsApiClient fflogsApiClient;
    private static final long PRE_PULL_CONTEXT_MS = 60_000L;
    private static final long POST_FIGHT_PADDING_MS = 1_000L;
    private static final int TOP_SKILLS_PER_ACTOR = 10;
    private static final double SEVERE_PARITY_GAP_RATIO = 0.50;
    private static final double MODERATE_PARITY_GAP_RATIO = 0.30;
    private static final int UNKNOWN_SKILL_WARNING_COUNT = 3;

    public SubmissionParityReportService(
            ActLineParser parser,
            ObjectMapper objectMapper,
            FflogsZoneLookup fflogsZoneLookup,
            EnrageTimeProvider enrageTimeProvider,
            FflogsApiClient fflogsApiClient
    ) {
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.fflogsZoneLookup = fflogsZoneLookup;
        this.enrageTimeProvider = enrageTimeProvider;
        this.fflogsApiClient = fflogsApiClient;
    }

    public SubmissionParityReport buildReport(String submissionId) throws IOException {
        Path submissionDir = Path.of("data", "submissions", submissionId);
        Path metadataPath = submissionDir.resolve("metadata.json");
        Path combatLogPath = submissionDir.resolve("combat.log");

        if (!Files.exists(metadataPath)) {
            throw new IOException("metadata not found: " + metadataPath);
        }
        if (!Files.exists(combatLogPath)) {
            throw new IOException("combat log not found: " + combatLogPath);
        }

        SubmissionParityReport.SubmissionMetadata metadata = objectMapper.readValue(
                Files.readString(metadataPath, StandardCharsets.UTF_8),
                SubmissionParityReport.SubmissionMetadata.class
        );

        SubmissionParityReport.FflogsReportSummary fflogsSummary = buildFflogsSummary(metadata);
        Optional<ReplayWindow> replayWindow = deriveReplayWindow(fflogsSummary);
        ReplayRunResult replayResult = runReplay(
                combatLogPath,
                metadata.zoneId(),
                replayWindow
        );
        SubmissionParityReport.DamageTextMatchDiagnostics damageTextMatchDiagnostics =
                diagnoseDamageTextMatching(combatLogPath, replayWindow);
        ComparisonBundle comparisonBundle = buildComparisons(submissionDir, replayResult.snapshot(), fflogsSummary);
        return new SubmissionParityReport(
                metadata,
                replayResult.summary(),
                damageTextMatchDiagnostics,
                replayResult.snapshot(),
                fflogsSummary,
                comparisonBundle.comparisons(),
                comparisonBundle.unmatchedLocalActors(),
                comparisonBundle.unmatchedFflogsActors()
        );
    }

    private SubmissionParityReport.FflogsReportSummary buildFflogsSummary(
            SubmissionParityReport.SubmissionMetadata metadata
    ) {
        String reportUrl = metadata.fflogsReportUrl();
        if (reportUrl == null || reportUrl.isBlank()) {
            return new SubmissionParityReport.FflogsReportSummary(
                    "missing_report_url",
                    reportUrl,
                    null,
                    metadata.fflogsFightId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "metadata.fflogsReportUrl is empty"
            );
        }

        String reportCode = extractReportCode(reportUrl);
        if (reportCode == null) {
            return new SubmissionParityReport.FflogsReportSummary(
                    "invalid_report_url",
                    reportUrl,
                    null,
                    metadata.fflogsFightId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "failed to parse FFLogs report code from URL"
            );
        }

        if (!fflogsApiClient.isConfigured()) {
            return new SubmissionParityReport.FflogsReportSummary(
                    "no_token_configured",
                    reportUrl,
                    reportCode,
                    metadata.fflogsFightId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "FFLogs client credentials are not configured"
            );
        }

        return fflogsApiClient.fetchReportSummary(reportCode)
                .map(summary -> toSubmissionFflogsSummary(
                        reportUrl,
                        metadata.fflogsFightId(),
                        metadata.submittedAt(),
                        summary
                ))
                .orElseGet(() -> new SubmissionParityReport.FflogsReportSummary(
                        "fetch_failed",
                        reportUrl,
                        reportCode,
                        metadata.fflogsFightId(),
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        "failed to fetch FFLogs report summary"
                ));
    }

    private SubmissionParityReport.FflogsReportSummary toSubmissionFflogsSummary(
            String reportUrl,
            Integer selectedFightId,
            String submittedAt,
            FflogsApiClient.ReportSummary summary
    ) {
        FflogsApiClient.ReportFight selectedFight = null;
        if (selectedFightId != null) {
            for (FflogsApiClient.ReportFight fight : summary.fights()) {
                if (fight.id() == selectedFightId) {
                    selectedFight = fight;
                    break;
                }
            }
        }
        if (selectedFight == null) {
            selectedFight = chooseFight(summary.fights(), reportUrl, selectedFightId, summary.startTime(), submittedAt);
        }

        List<SubmissionParityReport.FflogsFightSummary> fights = summary.fights().stream()
                .map(fight -> new SubmissionParityReport.FflogsFightSummary(
                        fight.id(),
                        fight.name(),
                        fight.startTime(),
                        fight.endTime(),
                        fight.kill() ? "kill" : "wipe",
                        fight.encounterId()
                ))
                .toList();

        List<SubmissionParityReport.FflogsActorSummary> actors = List.of();
        long selectedFightDurationMs = selectedFight != null
                ? Math.max(0L, selectedFight.endTime() - selectedFight.startTime())
                : 0L;
        if (selectedFight != null) {
            actors = fflogsApiClient.fetchDamageDoneTable(summary.reportCode(), selectedFight.id()).stream()
                    .map(actor -> new SubmissionParityReport.FflogsActorSummary(
                            actor.id(),
                            actor.name(),
                            actor.type(),
                            actor.icon(),
                            actor.total(),
                            actor.activeTime(),
                            actor.totalRdps(),
                            actor.totalRdpsTaken(),
                            actor.totalRdpsGiven(),
                            toPerSecond(actor.totalRdps(), selectedFightDurationMs),
                            toPerSecond(actor.totalRdpsTaken(), selectedFightDurationMs),
                            toPerSecond(actor.totalRdpsGiven(), selectedFightDurationMs)
                    ))
                    .toList();
        }

        return new SubmissionParityReport.FflogsReportSummary(
                "ok",
                reportUrl,
                summary.reportCode(),
                selectedFight != null ? selectedFight.id() : selectedFightId,
                selectedFight != null ? selectedFight.name() : null,
                selectedFight != null ? (selectedFight.kill() ? "kill" : "wipe") : null,
                summary.startTime(),
                selectedFight != null ? selectedFightDurationMs : null,
                fights,
                actors,
                null
        );
    }

    private FflogsApiClient.ReportFight chooseFight(
            List<FflogsApiClient.ReportFight> fights,
            String reportUrl,
            Integer selectedFightId,
            long reportStartTime,
            String submittedAt
    ) {
        if (fights.isEmpty()) {
            return null;
        }
        String fightIdFromUrl = extractFightIdFromUrl(reportUrl);
        if (fightIdFromUrl != null) {
            try {
                int fightId = Integer.parseInt(fightIdFromUrl);
                for (FflogsApiClient.ReportFight fight : fights) {
                    if (fight.id() == fightId) {
                        return fight;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (selectedFightId != null) {
            for (FflogsApiClient.ReportFight fight : fights) {
                if (fight.id() == selectedFightId) {
                    return fight;
                }
            }
        }

        Long submittedAtMs = parseSubmittedAtMs(submittedAt);
        if (submittedAtMs != null) {
            FflogsApiClient.ReportFight nearestSubmittedFight = chooseNearestFightToSubmittedAt(
                    fights,
                    reportStartTime,
                    submittedAtMs
            );
            if (nearestSubmittedFight != null) {
                return nearestSubmittedFight;
            }
        }

        return fights.stream()
                .filter(FflogsApiClient.ReportFight::kill)
                .reduce((first, second) -> second)
                .orElseGet(() -> fights.get(fights.size() - 1));
    }

    private Long parseSubmittedAtMs(String submittedAt) {
        if (submittedAt == null || submittedAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(submittedAt).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private FflogsApiClient.ReportFight chooseNearestFightToSubmittedAt(
            List<FflogsApiClient.ReportFight> fights,
            long reportStartTime,
            long submittedAtMs
    ) {
        List<FflogsApiClient.ReportFight> meaningfulFights = fights.stream()
                .filter(this::isMeaningfulEncounterFight)
                .toList();
        if (meaningfulFights.isEmpty()) {
            meaningfulFights = fights;
        }

        FflogsApiClient.ReportFight nearestAfterSubmittedAt = meaningfulFights.stream()
                .filter(fight -> reportStartTime + fight.startTime() >= submittedAtMs)
                .min((left, right) -> Long.compare(
                        (reportStartTime + left.startTime()) - submittedAtMs,
                        (reportStartTime + right.startTime()) - submittedAtMs
                ))
                .orElse(null);
        if (nearestAfterSubmittedAt != null) {
            return nearestAfterSubmittedAt;
        }

        return meaningfulFights.stream()
                .min((left, right) -> Long.compare(
                        Math.abs((reportStartTime + left.startTime()) - submittedAtMs),
                        Math.abs((reportStartTime + right.startTime()) - submittedAtMs)
                ))
                .orElse(null);
    }

    private boolean isMeaningfulEncounterFight(FflogsApiClient.ReportFight fight) {
        return fight != null
                && fight.encounterId() > 0
                && fight.name() != null
                && !fight.name().isBlank()
                && !"Unknown".equalsIgnoreCase(fight.name());
    }

    private String extractReportCode(String reportUrl) {
        int reportsIndex = reportUrl.indexOf("/reports/");
        if (reportsIndex < 0) {
            return null;
        }
        String codePart = reportUrl.substring(reportsIndex + "/reports/".length());
        int queryIndex = codePart.indexOf('?');
        if (queryIndex >= 0) {
            codePart = codePart.substring(0, queryIndex);
        }
        int hashIndex = codePart.indexOf('#');
        if (hashIndex >= 0) {
            codePart = codePart.substring(0, hashIndex);
        }
        codePart = codePart.trim();
        return codePart.isBlank() ? null : codePart;
    }

    private String extractFightIdFromUrl(String reportUrl) {
        int fightIndex = reportUrl.indexOf("fight=");
        if (fightIndex < 0) {
            return null;
        }
        String value = reportUrl.substring(fightIndex + "fight=".length());
        int ampIndex = value.indexOf('&');
        if (ampIndex >= 0) {
            value = value.substring(0, ampIndex);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }
        value = value.trim();
        return value.isBlank() ? null : value;
    }

    private Optional<ReplayWindow> deriveReplayWindow(SubmissionParityReport.FflogsReportSummary fflogsSummary) {
        if (fflogsSummary == null
                || !"ok".equals(fflogsSummary.status())
                || fflogsSummary.reportStartTime() == null
                || fflogsSummary.selectedFightId() == null) {
            return Optional.empty();
        }

        for (SubmissionParityReport.FflogsFightSummary fight : fflogsSummary.fights()) {
            if (fight.id() != fflogsSummary.selectedFightId()) {
                continue;
            }
            long absoluteFightStartMs = fflogsSummary.reportStartTime() + fight.startTime();
            long absoluteFightEndMs = fflogsSummary.reportStartTime() + fight.endTime();
            return Optional.of(new ReplayWindow(
                    absoluteFightStartMs,
                    absoluteFightStartMs - PRE_PULL_CONTEXT_MS,
                    absoluteFightEndMs + POST_FIGHT_PADDING_MS
            ));
        }
        return Optional.empty();
    }

    private ComparisonBundle buildComparisons(
            Path submissionDir,
            CombatDebugSnapshot combat,
            SubmissionParityReport.FflogsReportSummary fflogs
    ) {
        if (combat == null || combat.actors() == null || combat.actors().isEmpty()) {
            return new ComparisonBundle(List.of(), List.of(), List.of());
        }
        if (fflogs == null || fflogs.actors() == null || fflogs.actors().isEmpty()) {
            List<SubmissionParityReport.UnmatchedLocalActor> unmatchedLocalActors = combat.actors().stream()
                    .filter(actor -> actor.totalDamage() > 0)
                    .map(actor -> new SubmissionParityReport.UnmatchedLocalActor(
                            actor.name(),
                            actor.jobId(),
                            actor.totalDamage()
                    ))
                    .toList();
            return new ComparisonBundle(List.of(), unmatchedLocalActors, List.of());
        }

        Map<String, String> originalToAlias = loadOriginalToAlias(submissionDir.resolve("mapping.json"));
        Map<String, CombatDebugSnapshot.ActorDebugEntry> localByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : combat.actors()) {
            localByName.put(actor.name(), actor);
        }
        Map<String, CombatDebugSnapshot.ActorDebugEntry> unmatchedLocal = new HashMap<>(localByName);

        List<SubmissionParityReport.ActorParityComparison> comparisons = new ArrayList<>();
        List<SubmissionParityReport.UnmatchedFflogsActor> unmatchedFflogsActors = new ArrayList<>();
        for (SubmissionParityReport.FflogsActorSummary fflogsActor : fflogs.actors()) {
            if (isExcludedFflogsActorType(fflogsActor.type())) {
                continue;
            }

            String originalName = fflogsActor.name();
            String localName = originalToAlias.getOrDefault(originalName, originalName);
            CombatDebugSnapshot.ActorDebugEntry localActor = localByName.get(localName);
            if (localActor == null) {
                unmatchedFflogsActors.add(new SubmissionParityReport.UnmatchedFflogsActor(
                        fflogsActor.id(),
                        fflogsActor.name(),
                        fflogsActor.type(),
                        fflogsActor.total()
                ));
                continue;
            }
            unmatchedLocal.remove(localActor.name());

            double totalDamageDelta = localActor.totalDamage() - fflogsActor.total();
            double totalDamageDeltaRatio = fflogsActor.total() > 0.0
                    ? totalDamageDelta / fflogsActor.total()
                    : 0.0;
            double localDpsPerSecond = toPerSecond(localActor.totalDamage(), combat.elapsedMs());
            double localReceivedBuffPerSecond = toPerSecond(localActor.receivedBuffContribution(), combat.elapsedMs());
            double localGrantedBuffPerSecond = toPerSecond(localActor.grantedBuffContribution(), combat.elapsedMs());
            double localExternalDeltaPerSecond = localGrantedBuffPerSecond - localReceivedBuffPerSecond;
            double localDerivedRdpsPerSecond = localDpsPerSecond + localExternalDeltaPerSecond;
            double fflogsDpsPerSecond = toPerSecond(fflogsActor.total(), fflogs.selectedFightDurationMs());
            double fflogsExternalDeltaPerSecond =
                    fflogsActor.rdpsGivenPerSecond() - fflogsActor.rdpsTakenPerSecond();
            double receivedDeltaPerSecond = localReceivedBuffPerSecond - fflogsActor.rdpsTakenPerSecond();
            double receivedDeltaRatio = Math.abs(fflogsActor.rdpsTakenPerSecond()) > 0.0
                    ? receivedDeltaPerSecond / fflogsActor.rdpsTakenPerSecond()
                    : 0.0;
            double grantedDeltaPerSecond = localGrantedBuffPerSecond - fflogsActor.rdpsGivenPerSecond();
            double grantedDeltaRatio = Math.abs(fflogsActor.rdpsGivenPerSecond()) > 0.0
                    ? grantedDeltaPerSecond / fflogsActor.rdpsGivenPerSecond()
                    : 0.0;
            double externalDeltaPerSecond = localExternalDeltaPerSecond - fflogsExternalDeltaPerSecond;
            double externalDeltaRatio = Math.abs(fflogsExternalDeltaPerSecond) > 0.0
                    ? externalDeltaPerSecond / fflogsExternalDeltaPerSecond
                    : 0.0;
            double derivedRdpsDelta = localDerivedRdpsPerSecond - fflogsActor.rdpsPerSecond();
            double derivedRdpsDeltaRatio = Math.abs(fflogsActor.rdpsPerSecond()) > 0.0
                    ? derivedRdpsDelta / fflogsActor.rdpsPerSecond()
                    : 0.0;
            double rdpsDelta = localActor.onlineRdps() - fflogsActor.rdpsPerSecond();
            double rdpsDeltaRatio = Math.abs(fflogsActor.rdpsPerSecond()) > 0.0
                    ? rdpsDelta / fflogsActor.rdpsPerSecond()
                    : 0.0;
            List<SubmissionParityReport.SkillBreakdownEntry> localTopSkills = combat.skillBreakdowns().stream()
                    .filter(breakdown -> breakdown.actorId().equals(localActor.actorId()))
                    .findFirst()
                    .map(CombatDebugSnapshot.ActorSkillBreakdown::skills)
                    .orElse(List.of())
                    .stream()
                    .limit(TOP_SKILLS_PER_ACTOR)
                    .map(skill -> new SubmissionParityReport.SkillBreakdownEntry(
                            skill.skillName(),
                            skill.totalDamage(),
                            skill.hitCount()
                    ))
                    .toList();
            List<SubmissionParityReport.SkillBreakdownEntry> fflogsTopSkills = List.of();
            if (fflogs.reportCode() != null && fflogs.selectedFightId() != null && fflogsActor.id() != null) {
                fflogsTopSkills = fflogsApiClient.fetchDamageDoneAbilities(
                                fflogs.reportCode(),
                                fflogs.selectedFightId(),
                                fflogsActor.id()
                        ).stream()
                        .sorted((left, right) -> Double.compare(right.total(), left.total()))
                        .limit(TOP_SKILLS_PER_ACTOR)
                        .map(skill -> new SubmissionParityReport.SkillBreakdownEntry(
                                skill.name(),
                                Math.round(skill.total()),
                                0L
                        ))
                        .toList();
            }
            List<String> warningReasons = buildWarningReasons(
                    fflogsActor,
                    totalDamageDeltaRatio,
                    derivedRdpsDeltaRatio,
                    localTopSkills
            );

            comparisons.add(new SubmissionParityReport.ActorParityComparison(
                    "name_mapping",
                    localActor.name(),
                    originalName,
                    localActor.jobId(),
                    localActor.totalDamage(),
                    localDpsPerSecond,
                    localActor.receivedBuffContribution(),
                    localReceivedBuffPerSecond,
                    localActor.grantedBuffContribution(),
                    localGrantedBuffPerSecond,
                    localExternalDeltaPerSecond,
                    localDerivedRdpsPerSecond,
                    localActor.onlineRdps(),
                    fflogsActor.id(),
                    fflogsActor.name(),
                    fflogsActor.type(),
                    fflogsActor.total(),
                    fflogsDpsPerSecond,
                    fflogsActor.totalRdps(),
                    fflogsActor.totalRdpsTaken(),
                    fflogsActor.totalRdpsGiven(),
                    fflogsActor.rdpsPerSecond(),
                    fflogsActor.rdpsTakenPerSecond(),
                    fflogsActor.rdpsGivenPerSecond(),
                    fflogsExternalDeltaPerSecond,
                    receivedDeltaPerSecond,
                    receivedDeltaRatio,
                    grantedDeltaPerSecond,
                    grantedDeltaRatio,
                    externalDeltaPerSecond,
                    externalDeltaRatio,
                    totalDamageDelta,
                    totalDamageDeltaRatio,
                    derivedRdpsDelta,
                    derivedRdpsDeltaRatio,
                    rdpsDelta,
                    rdpsDeltaRatio,
                    !warningReasons.isEmpty(),
                    warningReasons,
                    localTopSkills,
                    fflogsTopSkills
            ));
        }
        comparisons.sort((left, right) -> Double.compare(
                Math.abs(right.rdpsDeltaRatio()),
                Math.abs(left.rdpsDeltaRatio())
        ));
        List<SubmissionParityReport.UnmatchedLocalActor> unmatchedLocalActors = unmatchedLocal.values().stream()
                .filter(actor -> actor.totalDamage() > 0)
                .map(actor -> new SubmissionParityReport.UnmatchedLocalActor(
                        actor.name(),
                        actor.jobId(),
                        actor.totalDamage()
                ))
                .sorted((left, right) -> Long.compare(right.localTotalDamage(), left.localTotalDamage()))
                .toList();
        unmatchedFflogsActors.sort((left, right) -> Double.compare(right.fflogsTotal(), left.fflogsTotal()));
        return new ComparisonBundle(comparisons, unmatchedLocalActors, unmatchedFflogsActors);
    }

    private boolean isExcludedFflogsActorType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return "Pet".equalsIgnoreCase(type)
                || "LimitBreak".equalsIgnoreCase(type);
    }

    private double toPerSecond(double total, long durationMs) {
        if (durationMs <= 0L) {
            return 0.0;
        }
        return total / (durationMs / 1000.0);
    }

    private List<String> buildWarningReasons(
            SubmissionParityReport.FflogsActorSummary fflogsActor,
            double totalDamageDeltaRatio,
            double derivedRdpsDeltaRatio,
            List<SubmissionParityReport.SkillBreakdownEntry> localTopSkills
    ) {
        List<String> reasons = new ArrayList<>();
        double totalGap = Math.abs(totalDamageDeltaRatio);
        double rdpsGap = Math.abs(derivedRdpsDeltaRatio);

        if (totalGap >= SEVERE_PARITY_GAP_RATIO) {
            reasons.add("severe_total_damage_gap");
        } else if (totalGap >= MODERATE_PARITY_GAP_RATIO) {
            reasons.add("moderate_total_damage_gap");
        }

        if (rdpsGap >= SEVERE_PARITY_GAP_RATIO) {
            reasons.add("severe_rdps_gap");
        } else if (rdpsGap >= MODERATE_PARITY_GAP_RATIO) {
            reasons.add("moderate_rdps_gap");
        }

        long unknownSkillCount = localTopSkills.stream()
                .map(SubmissionParityReport.SkillBreakdownEntry::skillName)
                .filter(this::isUnknownSkillName)
                .count();
        if (unknownSkillCount >= UNKNOWN_SKILL_WARNING_COUNT) {
            reasons.add("high_unknown_skill_ratio");
        }

        if ("Pictomancer".equalsIgnoreCase(fflogsActor.type())
                && (totalGap >= SEVERE_PARITY_GAP_RATIO
                || rdpsGap >= SEVERE_PARITY_GAP_RATIO
                || unknownSkillCount >= UNKNOWN_SKILL_WARNING_COUNT)) {
            reasons.add("pct_event_coverage_incomplete");
        }

        return reasons;
    }

    private boolean isUnknownSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return true;
        }
        return skillName.startsWith("Player")
                || skillName.startsWith("DoT#0");
    }

    private Map<String, String> loadOriginalToAlias(Path mappingPath) {
        if (!Files.exists(mappingPath)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    Files.readString(mappingPath, StandardCharsets.UTF_8),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class)
            );
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ReplayRunResult runReplay(
            Path combatLogPath,
            int territoryId,
            Optional<ReplayWindow> replayWindow
    ) throws IOException {
        CombatEngine engine = new CombatEngine();
        CombatService combatService = new CombatService(
                engine,
                snapshot -> {
                },
                (fightName, actTerritoryId) -> Optional.empty(),
                this.enrageTimeProvider
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                fflogsZoneLookup
        );

        long totalLines = 0L;
        long parsedLines = 0L;
        Map<Long, SkillAccumulator> skillAccumulators = new HashMap<>();
        CombatDebugSnapshot lastMeaningfulSnapshot = null;

        try (BufferedReader reader = Files.newBufferedReader(combatLogPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!shouldIncludeLine(line, replayWindow)) {
                    continue;
                }
                totalLines++;
                ParsedLine parsed = parser.parse(line);
                if (parsed == null) {
                    continue;
                }
                parsedLines++;
                accumulateSkillDamage(parsed, skillAccumulators, ingestion);
                ingestion.onParsed(parsed);
                CombatDebugSnapshot snapshot = combatService.debugSnapshot();
                if (isMeaningfulSnapshot(snapshot)) {
                    lastMeaningfulSnapshot = snapshot;
                }
            }
        }

        long elapsedMs = ingestion.nowElapsedMs();
        if (ingestion.isFightStarted()) {
            if (territoryId > 0) {
                combatService.onEvent(new CombatEvent.Tick(elapsedMs));
            } else {
                combatService.onEvent(new CombatEvent.Tick(elapsedMs));
            }
        }

        return new ReplayRunResult(
                new SubmissionParityReport.ReplaySummary(
                        combatLogPath.toAbsolutePath().toString(),
                        totalLines,
                        parsedLines,
                        totalLines - parsedLines,
                        ingestion.isFightStarted(),
                        elapsedMs
                ),
                attachSkillBreakdowns(
                        isMeaningfulSnapshot(combatService.debugSnapshot())
                                ? combatService.debugSnapshot()
                                : lastMeaningfulSnapshot,
                        skillAccumulators
                )
        );
    }

    private SubmissionParityReport.DamageTextMatchDiagnostics diagnoseDamageTextMatching(
            Path combatLogPath,
            Optional<ReplayWindow> replayWindow
    ) throws IOException {
        Deque<DamageText> recentTexts = new ArrayDeque<>();
        long damageTextLines = 0L;
        long abilityLines = 0L;
        long exactAmountCandidates = 0L;
        long exactAmountAndTargetCandidates = 0L;
        long exactAmountTargetAndSourceCandidates = 0L;

        try (BufferedReader reader = Files.newBufferedReader(combatLogPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !shouldIncludeLine(line, replayWindow)) {
                    continue;
                }
                ParsedLine parsed = parser.parse(line);
                if (parsed == null) {
                    continue;
                }
                if (parsed instanceof DamageText text) {
                    damageTextLines++;
                    recentTexts.addLast(text);
                    pruneDiagnosticDamageTexts(recentTexts, text.ts());
                    continue;
                }
                if (parsed instanceof NetworkAbilityRaw ability && ability.damage() > 0) {
                    abilityLines++;
                    pruneDiagnosticDamageTexts(recentTexts, ability.ts());
                    boolean amountMatched = false;
                    boolean amountTargetMatched = false;
                    boolean amountTargetSourceMatched = false;
                    for (DamageText text : recentTexts) {
                        long deltaMs = Math.abs(Duration.between(text.ts(), ability.ts()).toMillis());
                        if (deltaMs > 2_000) {
                            continue;
                        }
                        if (text.amount() != ability.damage()) {
                            continue;
                        }
                        amountMatched = true;
                        boolean targetMatches = text.targetTextName() == null
                                || text.targetTextName().isBlank()
                                || text.targetTextName().equals(ability.targetName());
                        if (!targetMatches) {
                            continue;
                        }
                        amountTargetMatched = true;
                        boolean sourceMatches = text.sourceTextName() == null
                                || text.sourceTextName().isBlank()
                                || text.sourceTextName().equals(ability.actorName());
                        if (sourceMatches) {
                            amountTargetSourceMatched = true;
                            break;
                        }
                    }
                    if (amountMatched) {
                        exactAmountCandidates++;
                    }
                    if (amountTargetMatched) {
                        exactAmountAndTargetCandidates++;
                    }
                    if (amountTargetSourceMatched) {
                        exactAmountTargetAndSourceCandidates++;
                    }
                }
            }
        }

        return new SubmissionParityReport.DamageTextMatchDiagnostics(
                damageTextLines,
                abilityLines,
                exactAmountCandidates,
                exactAmountAndTargetCandidates,
                exactAmountTargetAndSourceCandidates
        );
    }

    private void pruneDiagnosticDamageTexts(Deque<DamageText> recentTexts, Instant now) {
        while (!recentTexts.isEmpty()) {
            DamageText first = recentTexts.peekFirst();
            if (first == null) {
                return;
            }
            long ageMs = Math.abs(Duration.between(first.ts(), now).toMillis());
            if (ageMs <= 3_000) {
                return;
            }
            recentTexts.removeFirst();
        }
    }

    private boolean isMeaningfulSnapshot(CombatDebugSnapshot snapshot) {
        return snapshot != null
                && snapshot.territoryId() > 0
                && snapshot.actors() != null
                && !snapshot.actors().isEmpty();
    }

    private CombatDebugSnapshot attachSkillBreakdowns(
            CombatDebugSnapshot snapshot,
            Map<Long, SkillAccumulator> accumulators
    ) {
        if (snapshot == null) {
            return null;
        }
        List<CombatDebugSnapshot.ActorSkillBreakdown> skillBreakdowns = snapshot.actors().stream()
                .map(actor -> {
                    SkillAccumulator accumulator = accumulators.get(actor.actorId().value());
                    List<CombatDebugSnapshot.SkillDebugEntry> skills = accumulator == null
                            ? List.of()
                            : accumulator.skills().values().stream()
                            .map(skill -> new CombatDebugSnapshot.SkillDebugEntry(
                                    skill.skillName(),
                                    skill.totalDamage(),
                                    skill.hitCount()
                            ))
                            .sorted((left, right) -> Long.compare(right.totalDamage(), left.totalDamage()))
                            .limit(TOP_SKILLS_PER_ACTOR)
                            .toList();
                    return new CombatDebugSnapshot.ActorSkillBreakdown(actor.actorId(), actor.name(), skills);
                })
                .filter(breakdown -> !breakdown.skills().isEmpty())
                .toList();
        return new CombatDebugSnapshot(
                snapshot.fightName(),
                snapshot.phase(),
                snapshot.elapsedMs(),
                snapshot.territoryId(),
                snapshot.currentPlayerId(),
                snapshot.currentPlayer(),
                snapshot.actors(),
                snapshot.boss(),
                snapshot.enrage(),
                skillBreakdowns
        );
    }

    private void accumulateSkillDamage(
            ParsedLine parsed,
            Map<Long, SkillAccumulator> accumulators,
            ActIngestionService ingestion
    ) {
        if (parsed instanceof NetworkAbilityRaw ability
                && ability.damage() > 0
                && ingestion.wouldEmitDamage(ability)) {
            SkillAccumulator accumulator = accumulators.computeIfAbsent(
                    ability.actorId(),
                    ignored -> new SkillAccumulator()
            );
            accumulator.add(skillKey(ability.skillName(), ability.skillId()), ability.damage());
            return;
        }
        if (parsed instanceof DotTickRaw dot
                && dot.damage() > 0
                && dot.isDot()
                && ingestion.wouldEmitDotDamage(dot)) {
            SkillAccumulator accumulator = accumulators.computeIfAbsent(
                    dot.sourceId(),
                    ignored -> new SkillAccumulator()
            );
            accumulator.add("DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase(), dot.damage());
        }
    }

    private String skillKey(String skillName, int skillId) {
        if (skillName == null || skillName.isBlank()) {
            return "Skill#" + Integer.toHexString(skillId).toUpperCase();
        }
        return skillName + " (" + Integer.toHexString(skillId).toUpperCase() + ")";
    }

    private boolean shouldIncludeLine(String line, Optional<ReplayWindow> replayWindow) {
        if (replayWindow.isEmpty()) {
            return true;
        }

        String[] parts = line.split("\\|", 4);
        if (parts.length < 3) {
            return false;
        }

        String type = parts[0];
        String subtype = parts.length >= 3 ? parts[2] : "";
        Long timestampMs = parseLineTimestampMs(parts[1]);
        if (timestampMs == null) {
            return false;
        }

        ReplayWindow window = replayWindow.orElseThrow();
        if (timestampMs > window.endInclusiveMs()) {
            return false;
        }
        if (timestampMs >= window.fightStartMs()) {
            return true;
        }
        if (timestampMs >= window.startInclusiveMs()) {
            return isPrePullContextLineType(type, subtype);
        }
        return isHistoricalContextLineType(type, subtype);
    }

    private Long parseLineTimestampMs(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPrePullContextLineType(String type, String subtype) {
        return "01".equals(type)
                || "02".equals(type)
                || "03".equals(type)
                || "11".equals(type)
                || ("261".equals(type) && "Add".equals(subtype))
                || "26".equals(type)
                || "30".equals(type);
    }

    private boolean isHistoricalContextLineType(String type, String subtype) {
        return "01".equals(type)
                || "02".equals(type)
                || "03".equals(type)
                || "11".equals(type)
                || ("261".equals(type) && "Add".equals(subtype));
    }

    private record ReplayRunResult(
            SubmissionParityReport.ReplaySummary summary,
            CombatDebugSnapshot snapshot
    ) {
    }

    private record ComparisonBundle(
            List<SubmissionParityReport.ActorParityComparison> comparisons,
            List<SubmissionParityReport.UnmatchedLocalActor> unmatchedLocalActors,
            List<SubmissionParityReport.UnmatchedFflogsActor> unmatchedFflogsActors
    ) {
    }

    private record ReplayWindow(long fightStartMs, long startInclusiveMs, long endInclusiveMs) {
    }

    private static final class SkillAccumulator {
        private final Map<String, SkillStat> skills = new HashMap<>();

        void add(String skillName, long damage) {
            SkillStat current = skills.get(skillName);
            if (current == null) {
                skills.put(skillName, new SkillStat(skillName, damage, 1L));
                return;
            }
            skills.put(skillName, new SkillStat(skillName, current.totalDamage() + damage, current.hitCount() + 1L));
        }

        Map<String, SkillStat> skills() {
            return skills;
        }
    }

    private record SkillStat(String skillName, long totalDamage, long hitCount) {
    }
}
