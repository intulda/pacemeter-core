package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.BuffApplyRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.NetworkAbilityRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.model.ActorId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityReportDiagnostics {
    private static final long HEAVY4_REPORT_START_MS = 1_773_563_660_543L;
    private static final long HEAVY4_FIGHT2_START_OFFSET_MS = 49_499L;
    private static final long HEAVY4_FIGHT2_END_OFFSET_MS = 440_399L;
    private static final long HEAVY4_PRE_PULL_CONTEXT_MS = 60_000L;
    private static final long HEAVY4_POST_FIGHT_PADDING_MS = 1_000L;

    @Test
    void debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");

        System.out.println("FFLogs status: " + report.fflogs().status());
        System.out.println("Report code: " + report.fflogs().reportCode());
        System.out.println("Selected fight: " + report.fflogs().selectedFightName());
        System.out.println("Comparisons: " + report.comparisons().size());
        System.out.println("Parity quality: " + report.parityQuality());

        report.comparisons().stream()
                .filter(comparison -> {
                    String name = comparison.localName();
                    return "생쥐".equals(name)
                            || "나성".equals(name)
                            || "후엔".equals(name)
                            || "치삐".equals(name);
                })
                .forEach(comparison -> {
                    System.out.printf(
                            "%s job=%s localDps=%.1f localDerived=%.1f localOnline=%.1f fflogs=%.1f delta=%.1f ratio=%.3f givenDelta=%.1f takenDelta=%.1f totalDelta=%.1f warnings=%s%n",
                            comparison.localName(),
                            comparison.fflogsType(),
                            comparison.localDpsPerSecond(),
                            comparison.localDerivedRdpsPerSecond(),
                            comparison.localOnlineRdps(),
                            comparison.fflogsRdpsPerSecond(),
                            comparison.rdpsDelta(),
                            comparison.rdpsDeltaRatio(),
                            comparison.grantedDeltaPerSecond(),
                            comparison.receivedDeltaPerSecond(),
                            comparison.totalDamageDelta(),
                            comparison.warningReasons()
                    );
                    System.out.println("  localTopSkills=" + comparison.localTopSkills());
                    System.out.println("  fflogsTopSkills=" + comparison.fflogsTopSkills());
                });
    }

    @Test
    void debugLindwurmFight8Parity_withConfiguredFflogsCredentials_printsActorDelta() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz");

        System.out.println("FFLogs status: " + report.fflogs().status());
        System.out.println("Report code: " + report.fflogs().reportCode());
        System.out.println("Selected fight: " + report.fflogs().selectedFightName());
        System.out.println("Selected fight id: " + report.fflogs().selectedFightId());
        System.out.println("Replay parsedLines: " + report.replay().parsedLines());
        System.out.println("Replay fightStarted: " + report.replay().fightStarted());
        if (report.combat() != null) {
            System.out.println("Combat actors: " + report.combat().actors().size());
            System.out.println("Combat skillBreakdowns: " + report.combat().skillBreakdowns().size());
        } else {
            System.out.println("Combat actors: <null>");
            System.out.println("Combat skillBreakdowns: <null>");
        }
        System.out.println("Comparisons: " + report.comparisons().size());
        System.out.println("Unmatched local actors: " + report.unmatchedLocalActors().size());
        System.out.println("Unmatched fflogs actors: " + report.unmatchedFflogsActors().size());
        System.out.println("Parity quality: " + report.parityQuality());

        report.comparisons().stream()
                .sorted((left, right) -> Double.compare(
                        Math.abs(right.rdpsDelta()),
                        Math.abs(left.rdpsDelta())
                ))
                .limit(8)
                .forEach(comparison -> {
                    System.out.printf(
                            "%s job=%s localDps=%.1f localDerived=%.1f localOnline=%.1f fflogs=%.1f delta=%.1f ratio=%.3f givenDelta=%.1f takenDelta=%.1f totalDelta=%.1f warnings=%s%n",
                            comparison.localName(),
                            comparison.fflogsType(),
                            comparison.localDpsPerSecond(),
                            comparison.localDerivedRdpsPerSecond(),
                            comparison.localOnlineRdps(),
                            comparison.fflogsRdpsPerSecond(),
                            comparison.rdpsDelta(),
                            comparison.rdpsDeltaRatio(),
                            comparison.grantedDeltaPerSecond(),
                            comparison.receivedDeltaPerSecond(),
                            comparison.totalDamageDelta(),
                            comparison.warningReasons()
                    );
                    System.out.println("  localTopSkills=" + comparison.localTopSkills());
                    System.out.println("  fflogsTopSkills=" + comparison.fflogsTopSkills());
                });
    }

    @Test
    void debugParityQualityRollup_withConfiguredFflogsCredentials_printsGateAndWorstActors() throws Exception {
        SubmissionParityReportService reportService = buildConfiguredHeavy4Service();
        SubmissionParityQualityService qualityService = new SubmissionParityQualityService(reportService);
        SubmissionParityQualityService.SubmissionParityQualityRollup rollup = qualityService.buildRollup();

        System.out.println("rollup.gate=" + rollup.gate());
        System.out.println("rollup.summary submissionsScanned=" + rollup.submissionsScanned()
                + " evaluated=" + rollup.submissionsEvaluated()
                + " failed=" + rollup.submissionsFailed()
                + " actorComparisons=" + rollup.actorComparisons()
                + " mape=" + rollup.meanAbsolutePercentageError()
                + " p95=" + rollup.p95AbsolutePercentageError()
                + " max=" + rollup.maxAbsolutePercentageError());
        System.out.println("rollup.jobs=" + rollup.jobs());
        System.out.println("rollup.worstActors=" + rollup.worstActors());
        System.out.println("rollup.submissions=" + rollup.submissions());
        System.out.println("rollup.failures=" + rollup.failures());

        assertNotNull(rollup);
        assertTrue(rollup.submissionsScanned() >= 1);
    }

    @Test
    void debugHeavy4SageDotAcceptance_printsReplayCounts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ActLineParser parser = new ActLineParser();
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {
                },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        long totalSageDots = 0L;
        long acceptedSageDots = 0L;
        long totalSageDotBuffApplies = 0L;

        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof BuffApplyRaw buffApply
                    && buffApply.sourceId() == 0x1013CC4BL
                    && buffApply.targetId() == 0x4000664CL
                    && buffApply.statusId() == 0x0A38) {
                totalSageDotBuffApplies++;
            }
            if (parsed instanceof DotTickRaw dot
                    && dot.sourceId() == 0x1013CC4BL
                    && dot.targetId() == 0x4000664CL
                    && dot.isDot()) {
                totalSageDots++;
                if (ingestion.wouldEmitDotDamage(dot)) {
                    acceptedSageDots++;
                } else if (totalSageDots <= 10) {
                    System.out.println(
                            "Rejected sage dot at " + dot.ts()
                                    + " jobId=" + ingestion.debugJobId(dot.sourceId())
                                    + " hasAction=" + ingestion.debugHasUnknownStatusDotAction(dot.sourceId(), dot.targetId())
                                    + " hasStatus=" + ingestion.debugHasUnknownStatusDotStatus(dot.sourceId(), dot.targetId())
                                    + " resolved=" + ingestion.resolveDotActionId(dot)
                                    + " raw=" + dot.rawLine()
                    );
                }
            }
            ingestion.onParsed(parsed);
        }

        System.out.println("sageDotBuffApplies=" + totalSageDotBuffApplies);
        System.out.println("sageDotsTotal=" + totalSageDots);
        System.out.println("sageDotsAccepted=" + acceptedSageDots);
    }

    @Test
    void debugHeavy4SelectedFightWindow_printsSageDotAcceptance() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        ObjectMapper objectMapper = new ObjectMapper();

        SubmissionParityReport.FflogsReportSummary summary = buildHeavy4FflogsSummary(service, objectMapper);
        Optional<?> replayWindow = deriveReplayWindow(service, summary);
        Method shouldIncludeLine = openShouldIncludeLine();

        ActLineParser parser = new ActLineParser();
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {
                },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        long includedLines = 0L;
        long totalSageDots = 0L;
        long acceptedSageDots = 0L;
        Map<Long, Long> dotsByTarget = new HashMap<>();
        Map<Long, Long> acceptedDotsByTarget = new HashMap<>();

        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            includedLines++;
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof DotTickRaw dot
                    && dot.sourceId() == 0x1013CC4BL
                    && dot.isDot()) {
                totalSageDots++;
                dotsByTarget.merge(dot.targetId(), 1L, Long::sum);
                if (ingestion.wouldEmitDotDamage(dot)) {
                    acceptedSageDots++;
                    acceptedDotsByTarget.merge(dot.targetId(), 1L, Long::sum);
                } else if (totalSageDots <= 10) {
                    System.out.println(
                            "Selected window rejected sage dot at " + dot.ts()
                                    + " jobId=" + ingestion.debugJobId(dot.sourceId())
                                    + " hasAction=" + ingestion.debugHasUnknownStatusDotAction(dot.sourceId(), dot.targetId())
                                    + " hasStatus=" + ingestion.debugHasUnknownStatusDotStatus(dot.sourceId(), dot.targetId())
                                    + " resolved=" + ingestion.resolveDotActionId(dot)
                                    + " raw=" + dot.rawLine()
                    );
                }
            }
            ingestion.onParsed(parsed);
        }

        System.out.println("selectedFightId=" + summary.selectedFightId());
        System.out.println("selectedFightName=" + summary.selectedFightName());
        System.out.println("includedLines=" + includedLines);
        System.out.println("selectedWindowSageDots=" + totalSageDots);
        System.out.println("selectedWindowAcceptedSageDots=" + acceptedSageDots);
        System.out.println("selectedWindowSageDotsByTarget=" + dotsByTarget);
        System.out.println("selectedWindowAcceptedSageDotsByTarget=" + acceptedDotsByTarget);
    }

    @Test
    void debugHeavy4FightSelection_printsFightTimelineAgainstSubmittedAt() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        ObjectMapper objectMapper = new ObjectMapper();
        SubmissionParityReport.SubmissionMetadata metadata = readHeavy4Metadata(objectMapper);
        SubmissionParityReport.FflogsReportSummary summary = buildHeavy4FflogsSummary(service, objectMapper);

        long submittedAtMs = Instant.parse(metadata.submittedAt()).toEpochMilli();
        System.out.println("submittedAt=" + metadata.submittedAt());
        System.out.println("reportStart=" + summary.reportStartTime());
        System.out.println("selectedFightId=" + summary.selectedFightId());

        for (SubmissionParityReport.FflogsFightSummary fight : summary.fights()) {
            long absoluteStartMs = summary.reportStartTime() + fight.startTime();
            long absoluteEndMs = summary.reportStartTime() + fight.endTime();
            System.out.printf(
                    "fight id=%d name=%s kill=%s absStart=%s absEnd=%s deltaStartMs=%d deltaEndMs=%d%n",
                    fight.id(),
                    fight.name(),
                    fight.kill(),
                    Instant.ofEpochMilli(absoluteStartMs),
                    Instant.ofEpochMilli(absoluteEndMs),
                    absoluteStartMs - submittedAtMs,
                    absoluteEndMs - submittedAtMs
            );
        }
    }

    @Test
    void debugHeavy4Fight2RawDotSignals_printsTrackedJobEvidence() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        ObjectMapper objectMapper = new ObjectMapper();

        SubmissionParityReport.FflogsReportSummary summary = buildHeavy4FflogsSummary(service, objectMapper);
        assertEquals(2, summary.selectedFightId());
        Optional<?> replayWindow = deriveReplayWindow(service, summary);
        Method shouldIncludeLine = openShouldIncludeLine();

        Map<String, Long> dot24Counts = new HashMap<>();
        Map<String, Long> dot24StatusZeroCounts = new HashMap<>();
        Map<String, Long> statusApplyCounts = new HashMap<>();
        Map<String, Long> statusSnapshotCounts = new HashMap<>();
        Map<String, String> firstDotSamples = new HashMap<>();
        Map<String, String> firstStatusApplySamples = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }

            String type = parts[0];
            if ("24".equals(type) && parts.length >= 19 && "DoT".equals(parts[4])) {
                String sourceId = parts[17];
                if ("1013CC4B".equals(sourceId)) {
                    dot24Counts.merge("SGE", 1L, Long::sum);
                    if ("0".equals(parts[5])) {
                        dot24StatusZeroCounts.merge("SGE", 1L, Long::sum);
                    }
                    firstDotSamples.putIfAbsent("SGE", line);
                } else if ("100B744D".equals(sourceId)) {
                    dot24Counts.merge("SCH", 1L, Long::sum);
                    if ("0".equals(parts[5])) {
                        dot24StatusZeroCounts.merge("SCH", 1L, Long::sum);
                    }
                    firstDotSamples.putIfAbsent("SCH", line);
                } else if ("101589A6".equals(sourceId)) {
                    dot24Counts.merge("DRG", 1L, Long::sum);
                    if ("0".equals(parts[5])) {
                        dot24StatusZeroCounts.merge("DRG", 1L, Long::sum);
                    }
                    firstDotSamples.putIfAbsent("DRG", line);
                }
                continue;
            }

            if ("26".equals(type) && parts.length >= 9) {
                if ("A38".equals(parts[2]) && "1013CC4B".equals(parts[5])) {
                    statusApplyCounts.merge("SGE", 1L, Long::sum);
                    firstStatusApplySamples.putIfAbsent("SGE", line);
                } else if ("767".equals(parts[2]) && "100B744D".equals(parts[5])) {
                    statusApplyCounts.merge("SCH", 1L, Long::sum);
                    firstStatusApplySamples.putIfAbsent("SCH", line);
                } else if ("A9F".equals(parts[2]) && "101589A6".equals(parts[5])) {
                    statusApplyCounts.merge("DRG", 1L, Long::sum);
                    firstStatusApplySamples.putIfAbsent("DRG", line);
                }
                continue;
            }

            if ("38".equals(type)) {
                for (int i = 18; i <= parts.length - 3; i += 3) {
                    String statusId = parts[i];
                    if ("0A38".equals(statusId)) {
                        statusSnapshotCounts.merge("SGE", 1L, Long::sum);
                    } else if ("0767".equals(statusId)) {
                        statusSnapshotCounts.merge("SCH", 1L, Long::sum);
                    } else if ("0A9F".equals(statusId)) {
                        statusSnapshotCounts.merge("DRG", 1L, Long::sum);
                    }
                }
            }
        }

        for (String job : List.of("SGE", "SCH", "DRG")) {
            System.out.printf(
                    "%s dot24=%d dot24Status0=%d statusApply=%d statusSnapshot=%d%n",
                    job,
                    dot24Counts.getOrDefault(job, 0L),
                    dot24StatusZeroCounts.getOrDefault(job, 0L),
                    statusApplyCounts.getOrDefault(job, 0L),
                    statusSnapshotCounts.getOrDefault(job, 0L)
            );
            System.out.println("  firstDotSample=" + firstDotSamples.get(job));
            System.out.println("  firstStatusApplySample=" + firstStatusApplySamples.get(job));
        }
    }

    @Test
    void debugHeavy4Fight2LineTypeDistribution_printsUnparsedCandidates() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        ObjectMapper objectMapper = new ObjectMapper();
        ActLineParser parser = new ActLineParser();

        SubmissionParityReport.FflogsReportSummary summary = buildHeavy4FflogsSummary(service, objectMapper);
        assertEquals(2, summary.selectedFightId());
        Optional<?> replayWindow = deriveReplayWindow(service, summary);
        Method shouldIncludeLine = openShouldIncludeLine();

        Map<String, Long> includedTypeCounts = new HashMap<>();
        Map<String, Long> parsedTypeCounts = new HashMap<>();
        Map<String, Long> unparsedTypeCounts = new HashMap<>();
        Map<String, String> firstUnparsedSamples = new HashMap<>();
        Map<String, String> firstTrackedSamples = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            String[] parts = line.split("\\|", -1);
            if (parts.length < 1) {
                continue;
            }

            String type = parts[0];
            includedTypeCounts.merge(type, 1L, Long::sum);

            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                parsedTypeCounts.merge(type, 1L, Long::sum);
                continue;
            }

            unparsedTypeCounts.merge(type, 1L, Long::sum);
            firstUnparsedSamples.putIfAbsent(type, line);

            if (line.contains("1013CC4B")
                    || line.contains("100B744D")
                    || line.contains("101589A6")
                    || line.contains("|0A38|")
                    || line.contains("|0767|")
                    || line.contains("|0A9F|")) {
                firstTrackedSamples.putIfAbsent(type, line);
            }
        }

        System.out.println("includedTypeCounts=" + includedTypeCounts);
        System.out.println("parsedTypeCounts=" + parsedTypeCounts);
        System.out.println("unparsedTypeCounts=" + unparsedTypeCounts);
        for (Map.Entry<String, Long> entry : unparsedTypeCounts.entrySet()) {
            System.out.printf(
                    "unparsedType=%s count=%d%n  sample=%s%n  trackedSample=%s%n",
                    entry.getKey(),
                    entry.getValue(),
                    firstUnparsedSamples.get(entry.getKey()),
                    firstTrackedSamples.get(entry.getKey())
            );
        }
    }

    @Test
    void debugHeavy4DamageTextDiagnostics_printsMatchCounts() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(objectMapper), objectMapper)
        );

        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");
        SubmissionParityReport.DamageTextMatchDiagnostics diagnostics = report.damageTextMatchDiagnostics();

        System.out.printf(
                "damageTextLines=%d abilityLines=%d exactAmount=%d exactAmountAndTarget=%d exactAmountTargetAndSource=%d%n",
                diagnostics.damageTextLines(),
                diagnostics.abilityLines(),
                diagnostics.exactAmountCandidates(),
                diagnostics.exactAmountAndTargetCandidates(),
                diagnostics.exactAmountTargetAndSourceCandidates()
        );

        assertTrue(diagnostics.damageTextLines() > 0);
    }

    @Test
    void debugHeavy4Fight2LocalUnknownSkillAttribution_printsActorBreakdown() throws Exception {
        ActLineParser parser = new ActLineParser();
        ObjectMapper objectMapper = new ObjectMapper();
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {
                },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Set<String> targetActors = Set.of("생쥐", "나성", "치삐", "후엔");
        Map<String, LocalSkillAccumulator> accumulators = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            if (!isInKnownHeavy4Fight2Window(line)) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.damage() > 0
                    && ingestion.wouldEmitDamage(ability)
                    && targetActors.contains(ability.actorName())) {
                accumulators.computeIfAbsent(ability.actorName(), ignored -> new LocalSkillAccumulator())
                        .add(skillKey(ability.skillName(), ability.skillId()), ability.damage());
            } else if (parsed instanceof DotTickRaw dot
                    && dot.damage() > 0
                    && dot.isDot()
                    && ingestion.wouldEmitDotDamage(dot)
                    && targetActors.contains(dot.sourceName())) {
                accumulators.computeIfAbsent(dot.sourceName(), ignored -> new LocalSkillAccumulator())
                        .add("DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase(), dot.damage());
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        for (String actorName : List.of("생쥐", "나성", "치삐", "후엔")) {
            LocalSkillAccumulator accumulator = accumulators.get(actorName);
            assertNotNull(accumulator, "missing accumulator for " + actorName);

            long unknownDamage = accumulator.skills().values().stream()
                    .filter(skill -> isUnknownSkillName(skill.skillName()))
                    .mapToLong(LocalSkillStat::totalDamage)
                    .sum();
            double unknownRatio = accumulator.totalDamage() > 0
                    ? (double) unknownDamage / accumulator.totalDamage()
                    : 0.0;

            System.out.printf(
                    "%s localTotal=%d unknownDamage=%d unknownRatio=%.3f%n",
                    actorName,
                    accumulator.totalDamage(),
                    unknownDamage,
                    unknownRatio
            );
            System.out.println("  localUnknownSkills="
                    + accumulator.skills().values().stream()
                    .filter(skill -> isUnknownSkillName(skill.skillName()))
                    .sorted((left, right) -> Long.compare(right.totalDamage(), left.totalDamage()))
                    .limit(12)
                    .map(skill -> skill.skillName() + ":" + skill.totalDamage() + "/" + skill.hitCount())
                    .toList());
        }
    }

    @Test
    void debugHeavy4Fight2LocalUnknownSkillNeighborPatterns_printsRawLineContext() throws Exception {
        ActLineParser parser = new ActLineParser();
        ObjectMapper objectMapper = new ObjectMapper();
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {
                },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Set<String> targetActors = Set.of("생쥐", "나성", "치삐");
        List<ParsedReplayLine> includedLines = new ArrayList<>();
        List<UnknownEvent> unknownEvents = new ArrayList<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        int includedIndex = 0;
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            if (!isInKnownHeavy4Fight2Window(line)) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            String[] parts = line.split("\\|", -1);
            String type = parts.length > 0 ? parts[0] : "";
            Instant ts = parseLineInstant(parts);
            includedLines.add(new ParsedReplayLine(includedIndex, ts, type, line, parsed));

            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.damage() > 0
                    && ingestion.wouldEmitDamage(ability)
                    && targetActors.contains(ability.actorName())) {
                String skillName = skillKey(ability.skillName(), ability.skillId());
                if (isUnknownSkillName(skillName)) {
                    unknownEvents.add(new UnknownEvent(ability.actorName(), skillName, ability.damage(), includedIndex));
                }
            } else if (parsed instanceof DotTickRaw dot
                    && dot.damage() > 0
                    && dot.isDot()
                    && ingestion.wouldEmitDotDamage(dot)
                    && targetActors.contains(dot.sourceName())) {
                String skillName = "DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase();
                if (isUnknownSkillName(skillName)) {
                    unknownEvents.add(new UnknownEvent(dot.sourceName(), skillName, dot.damage(), includedIndex));
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
            includedIndex++;
        }

        Map<String, List<UnknownEvent>> eventsByActorAndSkill = new HashMap<>();
        for (UnknownEvent event : unknownEvents) {
            eventsByActorAndSkill.computeIfAbsent(event.actorName() + "|" + event.skillName(), ignored -> new ArrayList<>())
                    .add(event);
        }

        List<String> interestingTypes = List.of("20", "37", "38", "39", "261", "264", "270");
        for (Map.Entry<String, List<UnknownEvent>> entry : eventsByActorAndSkill.entrySet()) {
            List<UnknownEvent> events = entry.getValue();
            Map<String, Long> nearbyTypeCounts = new HashMap<>();
            ArrayDeque<String> samples = new ArrayDeque<>();

            for (UnknownEvent event : events) {
                for (int offset = -6; offset <= 6; offset++) {
                    if (offset == 0) {
                        continue;
                    }
                    int neighborIndex = event.lineIndex() + offset;
                    if (neighborIndex < 0 || neighborIndex >= includedLines.size()) {
                        continue;
                    }
                    ParsedReplayLine neighbor = includedLines.get(neighborIndex);
                    if (!interestingTypes.contains(neighbor.type())) {
                        continue;
                    }
                    nearbyTypeCounts.merge(neighbor.type(), 1L, Long::sum);
                    if (samples.size() < 4) {
                        samples.addLast("type=" + neighbor.type() + " sample=" + neighbor.rawLine());
                    }
                }
            }

            UnknownEvent firstEvent = events.get(0);
            long totalUnknownDamage = events.stream().mapToLong(UnknownEvent::damage).sum();
            System.out.printf(
                    "%s count=%d totalDamage=%d nearbyTypes=%s%n",
                    entry.getKey(),
                    events.size(),
                    totalUnknownDamage,
                    nearbyTypeCounts
            );
            System.out.println("  firstUnknownRaw=" + includedLines.get(firstEvent.lineIndex()).rawLine());
            for (String sample : samples) {
                System.out.println("  " + sample);
            }
        }
    }

    @Test
    void debugHeavy4UnknownSkillAttribution_printsActorSkillBreakdownAndFflogsCandidates() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");
        assertEquals("ok", report.fflogs().status());

        Set<String> targetActors = Set.of("생쥐", "나성", "치삐");
        Map<String, CombatDebugSnapshot.ActorDebugEntry> localActorsByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : report.combat().actors()) {
            localActorsByName.put(actor.name(), actor);
        }
        Map<ActorId, List<CombatDebugSnapshot.SkillDebugEntry>> localSkillsByActorId = new HashMap<>();
        for (CombatDebugSnapshot.ActorSkillBreakdown breakdown : report.combat().skillBreakdowns()) {
            localSkillsByActorId.put(breakdown.actorId(), breakdown.skills());
        }

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (SubmissionParityReport.ActorParityComparison comparison : report.comparisons()) {
            if (!targetActors.contains(comparison.localName())) {
                continue;
            }

            CombatDebugSnapshot.ActorDebugEntry localActor = localActorsByName.get(comparison.localName());
            assertNotNull(localActor, "missing local actor for " + comparison.localName());

            List<CombatDebugSnapshot.SkillDebugEntry> localSkills =
                    localSkillsByActorId.getOrDefault(localActor.actorId(), List.of());
            long localUnknownDamage = localSkills.stream()
                    .filter(skill -> isUnknownSkillName(skill.skillName()))
                    .mapToLong(CombatDebugSnapshot.SkillDebugEntry::totalDamage)
                    .sum();
            double unknownRatio = localActor.totalDamage() > 0
                    ? (double) localUnknownDamage / localActor.totalDamage()
                    : 0.0;

            Set<Integer> localKnownIds = new HashSet<>();
            Set<String> localKnownNames = new HashSet<>();
            for (CombatDebugSnapshot.SkillDebugEntry skill : localSkills) {
                Integer localSkillId = extractLocalSkillId(skill.skillName());
                if (localSkillId != null && !isUnknownSkillName(skill.skillName())) {
                    localKnownIds.add(localSkillId);
                }
                String normalizedName = normalizeLocalSkillName(skill.skillName());
                if (normalizedName != null && !normalizedName.isBlank() && !isUnknownSkillName(skill.skillName())) {
                    localKnownNames.add(normalizedName);
                }
            }

            List<FflogsApiClient.AbilityDamageEntry> missingFflogsAbilities = new ArrayList<>();
            for (FflogsApiClient.AbilityDamageEntry ability : apiClient.fetchDamageDoneAbilities(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    comparison.fflogsActorId()
            )) {
                boolean matchedById = ability.guid() != null && localKnownIds.contains(ability.guid());
                boolean matchedByName = localKnownNames.contains(ability.name());
                if (!matchedById && !matchedByName) {
                    missingFflogsAbilities.add(ability);
                }
            }

            System.out.printf(
                    "%s job=%s localTotal=%d unknownDamage=%d unknownRatio=%.3f rdpsDelta=%.1f warningReasons=%s%n",
                    comparison.localName(),
                    comparison.fflogsType(),
                    localActor.totalDamage(),
                    localUnknownDamage,
                    unknownRatio,
                    comparison.rdpsDelta(),
                    comparison.warningReasons()
            );
            System.out.println("  localUnknownSkills="
                    + localSkills.stream()
                    .filter(skill -> isUnknownSkillName(skill.skillName()))
                    .sorted((left, right) -> Long.compare(right.totalDamage(), left.totalDamage()))
                    .limit(12)
                    .map(skill -> skill.skillName() + ":" + skill.totalDamage() + "/" + skill.hitCount())
                    .toList());
            System.out.println("  fflogsMissingCandidates="
                    + missingFflogsAbilities.stream()
                    .sorted((left, right) -> Double.compare(right.total(), left.total()))
                    .limit(12)
                    .map(ability -> ability.name() + "(" + formatGuid(ability.guid()) + "):" + Math.round(ability.total()))
                    .toList());
        }
    }

    @Test
    void debugHeavy4UnknownSkillNeighborPatterns_printsRawLineContextByActorAndSkill() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");
        assertEquals("ok", report.fflogs().status());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();

        ObjectMapper objectMapper = new ObjectMapper();
        ActLineParser parser = new ActLineParser();
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {
                },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Map<Long, String> actorNamesById = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : report.combat().actors()) {
            actorNamesById.put(actor.actorId().value(), actor.name());
        }
        Set<String> targetActors = Set.of("생쥐", "나성", "치삐");
        List<ParsedReplayLine> includedLines = new ArrayList<>();
        List<UnknownEvent> unknownEvents = new ArrayList<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "combat.log");
        int includedIndex = 0;
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            String[] parts = line.split("\\|", -1);
            String type = parts.length > 0 ? parts[0] : "";
            Instant ts = parts.length > 1 ? Instant.parse(parts[1]) : null;
            includedLines.add(new ParsedReplayLine(includedIndex, ts, type, line, parsed));

            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.damage() > 0
                    && ingestion.wouldEmitDamage(ability)) {
                String actorName = actorNamesById.getOrDefault(ability.actorId(), ability.actorName());
                if (targetActors.contains(actorName) && isUnknownSkillName(skillKey(ability.skillName(), ability.skillId()))) {
                    unknownEvents.add(new UnknownEvent(
                            actorName,
                            skillKey(ability.skillName(), ability.skillId()),
                            ability.damage(),
                            includedIndex
                    ));
                }
            } else if (parsed instanceof DotTickRaw dot
                    && dot.damage() > 0
                    && dot.isDot()
                    && ingestion.wouldEmitDotDamage(dot)) {
                String actorName = actorNamesById.getOrDefault(dot.sourceId(), dot.sourceName());
                String dotSkillName = "DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase();
                if (targetActors.contains(actorName) && isUnknownSkillName(dotSkillName)) {
                    unknownEvents.add(new UnknownEvent(
                            actorName,
                            dotSkillName,
                            dot.damage(),
                            includedIndex
                    ));
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
            includedIndex++;
        }

        Map<String, List<UnknownEvent>> eventsByActorAndSkill = new HashMap<>();
        for (UnknownEvent event : unknownEvents) {
            eventsByActorAndSkill.computeIfAbsent(event.actorName() + "|" + event.skillName(), ignored -> new ArrayList<>())
                    .add(event);
        }

        List<String> interestingTypes = List.of("20", "37", "38", "39", "261", "264", "270");
        for (Map.Entry<String, List<UnknownEvent>> entry : eventsByActorAndSkill.entrySet()) {
            List<UnknownEvent> events = entry.getValue();
            events.sort((left, right) -> Integer.compare(left.lineIndex(), right.lineIndex()));
            Map<String, Long> nearbyTypeCounts = new HashMap<>();
            ArrayDeque<String> samples = new ArrayDeque<>();

            for (UnknownEvent event : events) {
                for (int offset = -6; offset <= 6; offset++) {
                    if (offset == 0) {
                        continue;
                    }
                    int neighborIndex = event.lineIndex() + offset;
                    if (neighborIndex < 0 || neighborIndex >= includedLines.size()) {
                        continue;
                    }
                    ParsedReplayLine neighbor = includedLines.get(neighborIndex);
                    if (!interestingTypes.contains(neighbor.type())) {
                        continue;
                    }
                    nearbyTypeCounts.merge(neighbor.type(), 1L, Long::sum);
                    if (samples.size() < 4) {
                        samples.addLast("type=" + neighbor.type() + " sample=" + neighbor.rawLine());
                    }
                }
            }

            long totalUnknownDamage = events.stream().mapToLong(UnknownEvent::damage).sum();
            UnknownEvent firstEvent = events.get(0);
            System.out.printf(
                    "%s count=%d totalDamage=%d nearbyTypes=%s%n",
                    entry.getKey(),
                    events.size(),
                    totalUnknownDamage,
                    nearbyTypeCounts
            );
            System.out.println("  firstUnknownRaw=" + includedLines.get(firstEvent.lineIndex()).rawLine());
            for (String sample : samples) {
                System.out.println("  " + sample);
            }
        }
    }

    private static String envOrProperty(String envKey, String propertyKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return System.getProperty(propertyKey, "");
    }

    private static boolean isInKnownHeavy4Fight2Window(String line) {
        String[] parts = line.split("\\|", 4);
        if (parts.length < 2) {
            return false;
        }
        Instant ts = parseLineInstant(parts);
        if (ts == null) {
            return false;
        }
        long tsMs = ts.toEpochMilli();
        long fightStartMs = HEAVY4_REPORT_START_MS + HEAVY4_FIGHT2_START_OFFSET_MS;
        long startInclusiveMs = fightStartMs - HEAVY4_PRE_PULL_CONTEXT_MS;
        long endInclusiveMs = HEAVY4_REPORT_START_MS + HEAVY4_FIGHT2_END_OFFSET_MS + HEAVY4_POST_FIGHT_PADDING_MS;
        return tsMs >= startInclusiveMs && tsMs <= endInclusiveMs;
    }

    private static Instant parseLineInstant(String[] parts) {
        if (parts.length < 2) {
            return null;
        }
        try {
            return Instant.parse(parts[1]);
        } catch (Exception ignored) {
            return null;
        }
    }

    private SubmissionParityReportService buildConfiguredHeavy4Service() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                buildConfiguredApiClient(objectMapper)
        );
    }

    private FflogsApiClient buildConfiguredApiClient() throws Exception {
        return buildConfiguredApiClient(new ObjectMapper());
    }

    private FflogsApiClient buildConfiguredApiClient(ObjectMapper objectMapper) throws Exception {
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));
        return apiClient;
    }

    private SubmissionParityReport.SubmissionMetadata readHeavy4Metadata(ObjectMapper objectMapper) throws Exception {
        return objectMapper.readValue(
                Files.readString(
                        Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "metadata.json"),
                        StandardCharsets.UTF_8
                ),
                SubmissionParityReport.SubmissionMetadata.class
        );
    }

    private SubmissionParityReport.FflogsReportSummary buildHeavy4FflogsSummary(
            SubmissionParityReportService service,
            ObjectMapper objectMapper
    ) throws Exception {
        Method buildFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "buildFflogsSummary",
                SubmissionParityReport.SubmissionMetadata.class
        );
        buildFflogsSummary.setAccessible(true);
        return (SubmissionParityReport.FflogsReportSummary) buildFflogsSummary.invoke(
                service,
                readHeavy4Metadata(objectMapper)
        );
    }

    private Optional<?> deriveReplayWindow(
            SubmissionParityReportService service,
            SubmissionParityReport.FflogsReportSummary summary
    ) throws Exception {
        Method deriveReplayWindow = SubmissionParityReportService.class.getDeclaredMethod(
                "deriveReplayWindow",
                SubmissionParityReport.FflogsReportSummary.class
        );
        deriveReplayWindow.setAccessible(true);
        return (Optional<?>) deriveReplayWindow.invoke(service, summary);
    }

    private Method openShouldIncludeLine() throws Exception {
        Method shouldIncludeLine = SubmissionParityReportService.class.getDeclaredMethod(
                "shouldIncludeLine",
                String.class,
                Optional.class
        );
        shouldIncludeLine.setAccessible(true);
        return shouldIncludeLine;
    }

    private static boolean isUnknownSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return true;
        }
        return skillName.startsWith("Player")
                || skillName.startsWith("DoT#0");
    }

    private static Integer extractLocalSkillId(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        if (skillName.startsWith("DoT#")) {
            String hex = skillName.substring("DoT#".length());
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int start = skillName.lastIndexOf('(');
        int end = skillName.lastIndexOf(')');
        if (start < 0 || end <= start + 1) {
            return null;
        }
        try {
            return Integer.parseInt(skillName.substring(start + 1, end), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeLocalSkillName(String skillName) {
        if (skillName == null) {
            return null;
        }
        if (skillName.startsWith("DoT#")) {
            return skillName;
        }
        int start = skillName.lastIndexOf(" (");
        if (start < 0) {
            return skillName;
        }
        return skillName.substring(0, start);
    }

    private static String formatGuid(Integer guid) {
        return guid == null ? "null" : Integer.toHexString(guid).toUpperCase();
    }

    private static String skillKey(String skillName, int skillId) {
        if (skillName == null || skillName.isBlank()) {
            return "Skill#" + Integer.toHexString(skillId).toUpperCase();
        }
        return skillName + " (" + Integer.toHexString(skillId).toUpperCase() + ")";
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record ParsedReplayLine(
            int index,
            Instant timestamp,
            String type,
            String rawLine,
            ParsedLine parsed
    ) {
    }

    private record UnknownEvent(
            String actorName,
            String skillName,
            long damage,
            int lineIndex
    ) {
    }

    private static final class LocalSkillAccumulator {
        private final Map<String, LocalSkillStat> skills = new HashMap<>();
        private long totalDamage;

        void add(String skillName, long damage) {
            totalDamage += damage;
            LocalSkillStat current = skills.get(skillName);
            if (current == null) {
                skills.put(skillName, new LocalSkillStat(skillName, damage, 1L));
                return;
            }
            skills.put(skillName, new LocalSkillStat(skillName, current.totalDamage() + damage, current.hitCount() + 1L));
        }

        Map<String, LocalSkillStat> skills() {
            return skills;
        }

        long totalDamage() {
            return totalDamage;
        }
    }

    private record LocalSkillStat(
            String skillName,
            long totalDamage,
            long hitCount
    ) {
    }
}
