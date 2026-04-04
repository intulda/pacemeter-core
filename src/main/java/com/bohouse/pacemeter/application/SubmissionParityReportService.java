package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.DamageText;
import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.NetworkAbilityRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.inbound.actws.ZoneChanged;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActionNameLibrary;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final long SUBMITTED_AT_FIGHT_START_NEAR_WINDOW_MS = 15 * 60 * 1000L;
    // 일부 스킬은 21 damage candidate가 실제 확정 hit보다 많다. (37 sequence result로 확정 여부 판별)
    private static final Set<Integer> RESULT_CONFIRMED_SKILL_IDS = Set.of(
            0x8780 // PCT Rainbow Drip
    );
    private static final Map<Integer, Integer> TERRITORY_ENCOUNTER_ID_OVERRIDES = Map.of(
            1327, 105
    );

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
        return buildReportInternal(submissionId, null);
    }

    public SubmissionParityReport buildReportForFight(String submissionId, int fightId) throws IOException {
        return buildReportInternal(submissionId, fightId);
    }

    private SubmissionParityReport buildReportInternal(String submissionId, Integer forcedFightId) throws IOException {
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

        Map<String, String> originalToAlias = loadOriginalToAlias(submissionDir.resolve("mapping.json"));
        Map<String, String> aliasToOriginal = invertAliasMapping(originalToAlias);
        SubmissionParityReport.FflogsReportSummary fflogsSummary = buildFflogsSummary(metadata, forcedFightId);
        fflogsSummary = maybeRefineFightSelection(
                combatLogPath,
                metadata,
                fflogsSummary,
                originalToAlias,
                aliasToOriginal,
                forcedFightId
        );
        Optional<ReplayWindow> replayWindow = deriveReplayWindow(fflogsSummary);
        ReplayRunResult replayResult = runReplay(
                combatLogPath,
                metadata.zoneId(),
                replayWindow,
                aliasToOriginal
        );
        SubmissionParityReport.DamageTextMatchDiagnostics damageTextMatchDiagnostics =
                diagnoseDamageTextMatching(combatLogPath, replayWindow, aliasToOriginal);
        ComparisonBundle comparisonBundle = buildComparisons(submissionDir, replayResult.snapshot(), fflogsSummary);
        SubmissionParityReport.ParityQualitySummary parityQuality = computeParityQualitySummary(
                comparisonBundle.comparisons(),
                comparisonBundle.unmatchedLocalActors(),
                comparisonBundle.unmatchedFflogsActors()
        );
        return new SubmissionParityReport(
                metadata,
                replayResult.summary(),
                damageTextMatchDiagnostics,
                replayResult.snapshot(),
                fflogsSummary,
                parityQuality,
                comparisonBundle.comparisons(),
                comparisonBundle.unmatchedLocalActors(),
                comparisonBundle.unmatchedFflogsActors()
        );
    }

    private SubmissionParityReport.ParityQualitySummary computeParityQualitySummary(
            List<SubmissionParityReport.ActorParityComparison> comparisons,
            List<SubmissionParityReport.UnmatchedLocalActor> unmatchedLocalActors,
            List<SubmissionParityReport.UnmatchedFflogsActor> unmatchedFflogsActors
    ) {
        if (comparisons.isEmpty()) {
            return new SubmissionParityReport.ParityQualitySummary(
                    0,
                    unmatchedLocalActors.size(),
                    unmatchedFflogsActors.size(),
                    0.0,
                    0.0,
                    0.0,
                    0,
                    0.0,
                    0.0,
                    0.0
            );
        }

        List<Double> absolutePercentageErrors = comparisons.stream()
                .map(comparison -> {
                    double baseline = Math.max(Math.abs(comparison.fflogsRdpsPerSecond()), 1.0);
                    return Math.abs(comparison.rdpsDelta()) / baseline;
                })
                .sorted()
                .collect(Collectors.toList());

        double sum = absolutePercentageErrors.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / absolutePercentageErrors.size();
        double p95 = percentile(absolutePercentageErrors, 0.95);
        double max = absolutePercentageErrors.get(absolutePercentageErrors.size() - 1);

        int withinOne = 0;
        int withinThree = 0;
        int withinFive = 0;
        int outliers = 0;
        for (double ape : absolutePercentageErrors) {
            if (ape <= 0.01) {
                withinOne++;
            }
            if (ape <= 0.03) {
                withinThree++;
            }
            if (ape <= 0.05) {
                withinFive++;
            } else {
                outliers++;
            }
        }

        int count = absolutePercentageErrors.size();
        return new SubmissionParityReport.ParityQualitySummary(
                comparisons.size(),
                unmatchedLocalActors.size(),
                unmatchedFflogsActors.size(),
                mean,
                p95,
                max,
                outliers,
                (double) withinOne / count,
                (double) withinThree / count,
                (double) withinFive / count
        );
    }

    private static double percentile(List<Double> sortedValues, double ratio) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }

        double boundedRatio = Math.min(Math.max(ratio, 0.0), 1.0);
        double index = boundedRatio * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double weight = index - lower;
        return sortedValues.get(lower) * (1.0 - weight) + sortedValues.get(upper) * weight;
    }

    private SubmissionParityReport.FflogsReportSummary buildFflogsSummary(
            SubmissionParityReport.SubmissionMetadata metadata,
            Integer forcedFightId
    ) {
        String reportUrl = metadata.fflogsReportUrl();
        Integer selectedFightId = forcedFightId != null ? forcedFightId : metadata.fflogsFightId();
        boolean ignoreFightIdInUrl = forcedFightId != null;

        if (reportUrl == null || reportUrl.isBlank()) {
            return new SubmissionParityReport.FflogsReportSummary(
                    "missing_report_url",
                    reportUrl,
                    null,
                    selectedFightId,
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
                    selectedFightId,
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
                    selectedFightId,
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
                        selectedFightId,
                        metadata.zoneId(),
                        metadata.submittedAt(),
                        summary,
                        ignoreFightIdInUrl
                ))
                .orElseGet(() -> new SubmissionParityReport.FflogsReportSummary(
                        "fetch_failed",
                        reportUrl,
                        reportCode,
                        selectedFightId,
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
            int actZoneId,
            String submittedAt,
            FflogsApiClient.ReportSummary summary,
            boolean ignoreFightIdInUrl
    ) {
        Integer expectedEncounterId = resolveExpectedEncounterId(actZoneId);
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
            selectedFight = chooseFight(
                    summary.fights(),
                    reportUrl,
                    selectedFightId,
                    expectedEncounterId,
                    summary.startTime(),
                    submittedAt,
                    ignoreFightIdInUrl
            );
        } else if (selectedFightId == null
                && expectedEncounterId != null
                && selectedFight.encounterId() != expectedEncounterId) {
            Long submittedAtMs = parseSubmittedAtMs(submittedAt);
            if (submittedAtMs == null || !isNearSubmittedAt(summary.startTime(), selectedFight, submittedAtMs)) {
                selectedFight = chooseFight(
                        summary.fights(),
                        reportUrl,
                        null,
                        expectedEncounterId,
                        summary.startTime(),
                        submittedAt,
                        ignoreFightIdInUrl
                );
            }
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
            Integer expectedEncounterId,
            long reportStartTime,
            String submittedAt,
            boolean ignoreFightIdInUrl
    ) {
        if (fights.isEmpty()) {
            return null;
        }
        if (!ignoreFightIdInUrl) {
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
        }
        if (selectedFightId != null) {
            for (FflogsApiClient.ReportFight fight : fights) {
                if (fight.id() == selectedFightId) {
                    return fight;
                }
            }
        }

        if (expectedEncounterId != null) {
            List<FflogsApiClient.ReportFight> encounterMatchedFights = fights.stream()
                    .filter(fight -> fight.encounterId() == expectedEncounterId)
                    .toList();
            if (!encounterMatchedFights.isEmpty()) {
                List<FflogsApiClient.ReportFight> preferredEncounterFights = encounterMatchedFights.stream()
                        .filter(FflogsApiClient.ReportFight::kill)
                        .toList();
                if (preferredEncounterFights.isEmpty()) {
                    preferredEncounterFights = encounterMatchedFights;
                }

                Long submittedAtMs = parseSubmittedAtMs(submittedAt);
                if (submittedAtMs != null) {
                    FflogsApiClient.ReportFight nearestEncounterFight = chooseNearestFightToSubmittedAt(
                            preferredEncounterFights,
                            reportStartTime,
                            submittedAtMs
                    );
                    if (nearestEncounterFight != null) {
                        return nearestEncounterFight;
                    }
                }

                return preferredEncounterFights.stream()
                        .filter(FflogsApiClient.ReportFight::kill)
                        .reduce((first, second) -> second)
                        .orElse(preferredEncounterFights.get(preferredEncounterFights.size() - 1));
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

    private boolean isNearSubmittedAt(long reportStartTime, FflogsApiClient.ReportFight fight, long submittedAtMs) {
        long fightStartAbsMs = reportStartTime + fight.startTime();
        return Math.abs(fightStartAbsMs - submittedAtMs) <= SUBMITTED_AT_FIGHT_START_NEAR_WINDOW_MS;
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

    private Integer resolveExpectedEncounterId(int actZoneId) {
        Integer overrideEncounterId = TERRITORY_ENCOUNTER_ID_OVERRIDES.get(actZoneId);
        if (overrideEncounterId != null) {
            return overrideEncounterId;
        }
        return fflogsZoneLookup.resolve(actZoneId)
                .flatMap(zone -> {
                    List<FflogsApiClient.EncounterInfo> encounters =
                            fflogsApiClient.fetchZoneEncounters(zone.fflogsZoneId());
                    if (zone.encounterIndex() < 0 || zone.encounterIndex() >= encounters.size()) {
                        return Optional.empty();
                    }
                    return Optional.of(encounters.get(zone.encounterIndex()).id());
                })
                .orElse(null);
    }

    private boolean isMeaningfulEncounterFight(FflogsApiClient.ReportFight fight) {
        return fight != null
                && fight.encounterId() > 0
                && fight.name() != null
                && !fight.name().isBlank()
                && !"Unknown".equalsIgnoreCase(fight.name());
    }

    private boolean isMeaningfulEncounterFight(SubmissionParityReport.FflogsFightSummary fight) {
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
                    absoluteFightEndMs,
                    absoluteFightStartMs - PRE_PULL_CONTEXT_MS,
                    absoluteFightEndMs + POST_FIGHT_PADDING_MS
            ));
        }
        return Optional.empty();
    }

    private SubmissionParityReport.FflogsReportSummary maybeRefineFightSelection(
            Path combatLogPath,
            SubmissionParityReport.SubmissionMetadata metadata,
            SubmissionParityReport.FflogsReportSummary initialSummary,
            Map<String, String> originalToAlias,
            Map<String, String> aliasToOriginal,
            Integer forcedFightId
    ) throws IOException {
        if (initialSummary == null
                || !"ok".equals(initialSummary.status())
                || initialSummary.reportCode() == null
                || initialSummary.reportStartTime() == null
                || initialSummary.fights() == null
                || initialSummary.fights().size() <= 1
                || forcedFightId != null
                || metadata.fflogsFightId() != null
                || extractFightIdFromUrl(metadata.fflogsReportUrl()) != null) {
            return initialSummary;
        }

        double bestScore = Double.POSITIVE_INFINITY;
        SubmissionParityReport.FflogsFightSummary bestFight = null;
        List<SubmissionParityReport.FflogsActorSummary> bestActors = List.of();
        Integer expectedEncounterId = resolveExpectedEncounterId(metadata.zoneId());

        for (SubmissionParityReport.FflogsFightSummary fight : initialSummary.fights()) {
            if (!isMeaningfulEncounterFight(fight)) {
                continue;
            }

            ReplayWindow replayWindow = new ReplayWindow(
                    initialSummary.reportStartTime() + fight.startTime(),
                    initialSummary.reportStartTime() + fight.endTime(),
                    initialSummary.reportStartTime() + fight.startTime() - PRE_PULL_CONTEXT_MS,
                    initialSummary.reportStartTime() + fight.endTime() + POST_FIGHT_PADDING_MS
            );
            ReplayRunResult replay = runReplay(combatLogPath, metadata.zoneId(), Optional.of(replayWindow), aliasToOriginal);
            if (replay.snapshot() == null || replay.snapshot().actors() == null || replay.snapshot().actors().isEmpty()) {
                continue;
            }

            long fightDurationMs = Math.max(0L, fight.endTime() - fight.startTime());
            List<SubmissionParityReport.FflogsActorSummary> actors = fflogsApiClient
                    .fetchDamageDoneTable(initialSummary.reportCode(), fight.id()).stream()
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
                            toPerSecond(actor.totalRdps(), fightDurationMs),
                            toPerSecond(actor.totalRdpsTaken(), fightDurationMs),
                            toPerSecond(actor.totalRdpsGiven(), fightDurationMs)
                    ))
                    .toList();
            if (actors.isEmpty()) {
                continue;
            }

            double score = scoreFightMatch(replay.snapshot(), actors, originalToAlias, expectedEncounterId, fight.encounterId());
            if (score < bestScore) {
                bestScore = score;
                bestFight = fight;
                bestActors = actors;
            }
        }

        if (bestFight == null || bestFight.id() == initialSummary.selectedFightId()) {
            return initialSummary;
        }

        long selectedFightDurationMs = Math.max(0L, bestFight.endTime() - bestFight.startTime());
        return new SubmissionParityReport.FflogsReportSummary(
                initialSummary.status(),
                initialSummary.reportUrl(),
                initialSummary.reportCode(),
                bestFight.id(),
                bestFight.name(),
                bestFight.kill(),
                initialSummary.reportStartTime(),
                selectedFightDurationMs,
                initialSummary.fights(),
                bestActors,
                initialSummary.message()
        );
    }

    private double scoreFightMatch(
            CombatDebugSnapshot localCombat,
            List<SubmissionParityReport.FflogsActorSummary> fflogsActors,
            Map<String, String> originalToAlias,
            Integer expectedEncounterId,
            int actualEncounterId
    ) {
        Map<String, CombatDebugSnapshot.ActorDebugEntry> localByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : localCombat.actors()) {
            localByName.put(actor.name(), actor);
        }

        double score = 0.0;
        int matchedActors = 0;
        for (SubmissionParityReport.FflogsActorSummary fflogsActor : fflogsActors) {
            if (isExcludedFflogsActorType(fflogsActor.type())) {
                continue;
            }

            String localName = originalToAlias.getOrDefault(fflogsActor.name(), fflogsActor.name());
            CombatDebugSnapshot.ActorDebugEntry localActor = localByName.get(localName);
            if (localActor == null) {
                score += 5.0;
                continue;
            }

            matchedActors++;
            double total = Math.max(1.0, Math.abs(fflogsActor.total()));
            score += Math.abs(localActor.totalDamage() - fflogsActor.total()) / total;
        }

        if (matchedActors == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (expectedEncounterId != null && expectedEncounterId != actualEncounterId) {
            score += 0.25;
        }
        return score / matchedActors;
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
            List<SubmissionParityReport.SkillBreakdownEntry> localSkillEntries = combat.skillBreakdowns().stream()
                    .filter(breakdown -> breakdown.actorId().equals(localActor.actorId()))
                    .findFirst()
                    .map(CombatDebugSnapshot.ActorSkillBreakdown::skills)
                    .orElse(List.of())
                    .stream()
                    .collect(Collectors.toMap(
                            skill -> {
                                Integer guid = extractLocalSkillGuid(skill.skillName());
                                return guid != null && guid > 0
                                        ? "guid:" + Integer.toHexString(guid).toUpperCase()
                                        : "name:" + normalizeSkillKey(skill.skillName());
                            },
                            skill -> new SubmissionParityReport.SkillBreakdownEntry(
                                    extractLocalSkillGuid(skill.skillName()),
                                    skill.skillName(),
                                    skill.totalDamage(),
                                    skill.hitCount()
                            ),
                            (left, right) -> new SubmissionParityReport.SkillBreakdownEntry(
                                    left.skillGuid() != null ? left.skillGuid() : right.skillGuid(),
                                    left.skillName() != null && !left.skillName().isBlank()
                                            ? left.skillName()
                                            : right.skillName(),
                                    left.totalDamage() + right.totalDamage(),
                                    left.hitCount() + right.hitCount()
                            ),
                            LinkedHashMap::new
                    ))
                    .values()
                    .stream()
                    .sorted((left, right) -> Long.compare(right.totalDamage(), left.totalDamage()))
                    .toList();
            List<SubmissionParityReport.SkillBreakdownEntry> fflogsSkillEntries = List.of();
            if (fflogs.reportCode() != null && fflogs.selectedFightId() != null && fflogsActor.id() != null) {
                fflogsSkillEntries = fflogsApiClient.fetchDamageDoneAbilities(
                                fflogs.reportCode(),
                                fflogs.selectedFightId(),
                                fflogsActor.id()
                        ).stream()
                        .sorted((left, right) -> Double.compare(right.total(), left.total()))
                        .map(skill -> new SubmissionParityReport.SkillBreakdownEntry(
                                skill.guid(),
                                skill.name(),
                                Math.round(skill.total()),
                                0L
                        ))
                        .toList();
            }
            List<SubmissionParityReport.SkillBreakdownEntry> localTopSkills =
                    selectRelevantSkillSurface(localSkillEntries, fflogsSkillEntries);
            List<SubmissionParityReport.SkillBreakdownEntry> fflogsTopSkills =
                    selectRelevantSkillSurface(fflogsSkillEntries, localSkillEntries);
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

    private Integer extractLocalSkillGuid(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        if (skillName.startsWith("DoT#")) {
            return parseHexSuffix(skillName.substring("DoT#".length()));
        }
        if (skillName.startsWith("Skill#")) {
            return parseHexSuffix(skillName.substring("Skill#".length()));
        }
        int start = skillName.lastIndexOf('(');
        int end = skillName.lastIndexOf(')');
        if (start >= 0 && end > start + 1) {
            return parseHexSuffix(skillName.substring(start + 1, end));
        }
        return null;
    }

    private Integer parseHexSuffix(String value) {
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeSkillKey(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return "";
        }
        String normalized = skillName;
        int bracketStart = normalized.lastIndexOf(" (");
        int bracketEnd = normalized.lastIndexOf(')');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            normalized = normalized.substring(0, bracketStart);
        }
        return normalized.trim().toLowerCase();
    }

    private List<SubmissionParityReport.SkillBreakdownEntry> selectRelevantSkillSurface(
            List<SubmissionParityReport.SkillBreakdownEntry> primaryEntries,
            List<SubmissionParityReport.SkillBreakdownEntry> counterpartEntries
    ) {
        if (primaryEntries.isEmpty()) {
            return List.of();
        }

        Set<String> includedKeys = new HashSet<>();
        primaryEntries.stream()
                .limit(TOP_SKILLS_PER_ACTOR)
                .map(this::buildSkillMatchKey)
                .forEach(includedKeys::add);
        counterpartEntries.stream()
                .limit(TOP_SKILLS_PER_ACTOR)
                .map(this::buildSkillMatchKey)
                .forEach(includedKeys::add);

        return primaryEntries.stream()
                .filter(entry -> includedKeys.contains(buildSkillMatchKey(entry)))
                .toList();
    }

    private String buildSkillMatchKey(SubmissionParityReport.SkillBreakdownEntry entry) {
        if (entry.skillGuid() != null && entry.skillGuid() > 0) {
            return "guid:" + Integer.toHexString(entry.skillGuid()).toUpperCase();
        }
        return "name:" + normalizeSkillKey(entry.skillName());
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

    private Map<String, String> invertAliasMapping(Map<String, String> originalToAlias) {
        if (originalToAlias == null || originalToAlias.isEmpty()) {
            return Map.of();
        }
        Map<String, String> aliasToOriginal = new HashMap<>();
        for (Map.Entry<String, String> entry : originalToAlias.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            aliasToOriginal.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(aliasToOriginal);
    }

    private ReplayRunResult runReplay(
            Path combatLogPath,
            int territoryId,
            Optional<ReplayWindow> replayWindow,
            Map<String, String> aliasToOriginal
    ) throws IOException {
        CombatEngine engine = new CombatEngine();
        Map<Long, SkillAccumulator> skillAccumulators = new HashMap<>();
        CombatService combatService = new CombatService(
                engine,
                snapshot -> {
                },
                (fightName, actTerritoryId) -> Optional.empty(),
                this.enrageTimeProvider
        );
        CombatEventPort forwardingPort = new CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(CombatEvent event) {
                if (event instanceof CombatEvent.DamageEvent damageEvent) {
                    accumulateEmittedSkillDamage(damageEvent, skillAccumulators);
                }
                return combatService.onEvent(event);
            }

            @Override
            public void setCurrentPlayerId(com.bohouse.pacemeter.core.model.ActorId playerId) {
                combatService.setCurrentPlayerId(playerId);
            }

            @Override
            public void setJobId(com.bohouse.pacemeter.core.model.ActorId actorId, int jobId) {
                combatService.setJobId(actorId, jobId);
            }
        };
        ActIngestionService ingestion = new ActIngestionService(
                forwardingPort,
                combatService,
                fflogsZoneLookup
        );
        if (territoryId > 0) {
            Instant contextTs = replayWindow
                    .map(window -> Instant.ofEpochMilli(window.startInclusiveMs()))
                    .orElse(Instant.EPOCH);
            ingestion.onParsed(new ZoneChanged(contextTs, territoryId, ""));
        }

        long totalLines = 0L;
        long parsedLines = 0L;
        CombatDebugSnapshot lastMeaningfulSnapshot = null;
        Set<String> confirmedActionSequences = collectConfirmedActionSequences(combatLogPath, replayWindow);

        try (BufferedReader reader = Files.newBufferedReader(combatLogPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!shouldIncludeLine(line, replayWindow)) {
                    continue;
                }
                if (shouldSkipUnconfirmedResultDamage(line, confirmedActionSequences)) {
                    continue;
                }
                totalLines++;
                ParsedLine parsed = parser.parse(deanonymizeLine(line, aliasToOriginal));
                if (parsed == null) {
                    continue;
                }
                parsedLines++;
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

    private void accumulateEmittedSkillDamage(
            CombatEvent.DamageEvent damageEvent,
            Map<Long, SkillAccumulator> accumulators
    ) {
        long actorId = damageEvent.sourceId().value();
        SkillAccumulator accumulator = accumulators.computeIfAbsent(actorId, ignored -> new SkillAccumulator());
        String skillName = toDebugSkillName(damageEvent);
        accumulator.add(skillName, damageEvent.amount());
    }

    private String toDebugSkillName(CombatEvent.DamageEvent damageEvent) {
        int actionId = damageEvent.actionId();
        if (damageEvent.damageType() == com.bohouse.pacemeter.core.model.DamageType.DOT) {
            return "DoT#" + Integer.toHexString(actionId).toUpperCase();
        }
        String resolved = ActionNameLibrary.resolveKnown(actionId);
        if (resolved != null && !resolved.isBlank()) {
            return resolved + " (" + Integer.toHexString(actionId).toUpperCase() + ")";
        }
        return "Skill#" + Integer.toHexString(actionId).toUpperCase();
    }

    private Set<String> collectConfirmedActionSequences(
            Path combatLogPath,
            Optional<ReplayWindow> replayWindow
    ) throws IOException {
        if (RESULT_CONFIRMED_SKILL_IDS.isEmpty()) {
            return Set.of();
        }
        Set<String> sequences = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(combatLogPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !shouldIncludeLine(line, replayWindow)) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length > 4 && "37".equals(parts[0])) {
                    String sequence = parts[4];
                    if (!sequence.isBlank()) {
                        sequences.add(sequence);
                    }
                }
            }
        }
        return sequences;
    }

    private boolean shouldSkipUnconfirmedResultDamage(String line, Set<String> confirmedActionSequences) {
        if (confirmedActionSequences.isEmpty()) {
            return false;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length <= 44 || !"21".equals(parts[0])) {
            return false;
        }
        String rawDamage = parts[9];
        if (rawDamage == null || rawDamage.isBlank() || "0".equals(rawDamage)) {
            return false;
        }
        int skillId = parseHexInt(parts[4]);
        if (!RESULT_CONFIRMED_SKILL_IDS.contains(skillId)) {
            return false;
        }
        String sequence = parts[44];
        return sequence != null && !sequence.isBlank() && !confirmedActionSequences.contains(sequence);
    }

    private int parseHexInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private SubmissionParityReport.DamageTextMatchDiagnostics diagnoseDamageTextMatching(
            Path combatLogPath,
            Optional<ReplayWindow> replayWindow,
            Map<String, String> aliasToOriginal
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
                ParsedLine parsed = parser.parse(deanonymizeLine(line, aliasToOriginal));
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

    private String deanonymizeLine(String line, Map<String, String> aliasToOriginal) {
        if (line == null || line.isBlank() || aliasToOriginal == null || aliasToOriginal.isEmpty()) {
            return line;
        }
        String[] parts = line.split("\\|", -1);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String original = aliasToOriginal.get(parts[i]);
            if (original != null) {
                parts[i] = original;
                changed = true;
            }
        }
        if (!changed) {
            return line;
        }
        return String.join("|", parts);
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
        if (timestampMs > window.fightEndMs()) {
            return isPostFightContextLineType(type, subtype);
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

    private boolean isPostFightContextLineType(String type, String subtype) {
        return "01".equals(type)
                || "02".equals(type)
                || "03".equals(type)
                || "11".equals(type)
                || ("261".equals(type) && "Add".equals(subtype))
                || "26".equals(type)
                || "30".equals(type);
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

    private record ReplayWindow(long fightStartMs, long fightEndMs, long startInclusiveMs, long endInclusiveMs) {
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
