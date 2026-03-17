package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.BuffApplyRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityReportServiceTest {

    @Test
    void buildReport_readsRegisteredSubmissionAndBuildsCombatSnapshot() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.of(new EnrageTimeProvider.EnrageInfo(
                        480.0,
                        EnrageTimeProvider.ConfidenceLevel.HIGH,
                        "test://submission"
                )),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        SubmissionParityReport report = service.buildReport("2026-02-11-heavy3-pull1-full");

        assertEquals("2026-02-11-heavy3-pull1-full", report.metadata().submissionId());
        assertTrue(report.replay().fightStarted());
        assertTrue(report.replay().parsedLines() > 0);
        assertNotNull(report.damageTextMatchDiagnostics());
        assertNotNull(report.combat());
        assertFalse(report.combat().actors().isEmpty());
        assertNotNull(report.combat().boss());
        assertEquals("아르카디아 선수권: 헤비급(영웅) (3)", report.combat().fightName());
        assertEquals(1325, report.combat().territoryId());
        assertNotNull(report.combat().enrage());
        assertTrue(report.combat().actors().stream().anyMatch(actor -> actor.totalDamage() > 0));
        assertEquals("missing_report_url", report.fflogs().status());
        assertTrue(report.comparisons().isEmpty());
        assertFalse(report.unmatchedLocalActors().isEmpty());
        assertTrue(report.unmatchedFflogsActors().isEmpty());
    }

    @Test
    void buildReport_withFflogsUrl_degradesGracefullyWhenTokenIsNotConfigured() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(objectMapper), objectMapper)
        );

        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");

        assertEquals("2026-03-15-heavy4-vafpbaqjnhbk1mtw", report.metadata().submissionId());
        assertEquals("no_token_configured", report.fflogs().status());
        assertEquals("VAfPBaqJnHbK1Mtw", report.fflogs().reportCode());
        assertNotNull(report.damageTextMatchDiagnostics());
        assertTrue(report.fflogs().fights().isEmpty());
        assertTrue(report.comparisons().isEmpty());
        assertFalse(report.unmatchedLocalActors().isEmpty());
        assertTrue(report.unmatchedFflogsActors().isEmpty());
    }

    @Test
    void buildReport_filtersFriendlyTargetSkillsFromLocalBreakdown() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(objectMapper), objectMapper)
        );

        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");
        assertTrue(report.damageTextMatchDiagnostics().damageTextLines() > 0);
        assertTrue(report.damageTextMatchDiagnostics().abilityLines() > 0);

        CombatDebugSnapshot.ActorSkillBreakdown pictomancer = report.combat().skillBreakdowns().stream()
                .filter(actor -> "이끼이끼".equals(actor.actorName()))
                .findFirst()
                .orElseThrow();

        CombatDebugSnapshot.SkillDebugEntry friendlyTargetSkill = pictomancer.skills().stream()
                .filter(skill -> skill.skillName().contains("(877A)"))
                .findFirst()
                .orElse(null);

        assertNull(friendlyTargetSkill);
    }

    @Test
    void chooseFight_prefersNearestMeaningfulFightAfterSubmittedAt() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        List<FflogsApiClient.ReportFight> fights = List.of(
                new FflogsApiClient.ReportFight(1, "Unknown", 22_855L, 27_712L, false, 0),
                new FflogsApiClient.ReportFight(2, "Lindwurm", 49_499L, 440_399L, true, 1079),
                new FflogsApiClient.ReportFight(3, "Lindwurm", 536_610L, 546_016L, false, 1079),
                new FflogsApiClient.ReportFight(4, "Lindwurm", 584_604L, 733_027L, false, 1079),
                new FflogsApiClient.ReportFight(5, "Lindwurm", 758_392L, 1_285_394L, true, 1079)
        );

        Method chooseFight = SubmissionParityReportService.class.getDeclaredMethod(
                "chooseFight",
                List.class,
                String.class,
                Integer.class,
                long.class,
                String.class
        );
        chooseFight.setAccessible(true);

        FflogsApiClient.ReportFight selectedFight = (FflogsApiClient.ReportFight) chooseFight.invoke(
                service,
                fights,
                "https://ko.fflogs.com/reports/VAfPBaqJnHbK1Mtw",
                null,
                1_773_563_660_543L,
                "2026-03-15T17:34:20+09:00"
        );

        assertNotNull(selectedFight);
        assertEquals(2, selectedFight.id());
    }

    @Test
    void debugHeavy4Parity_withConfiguredFflogsCredentials_printsActorDelta() throws Exception {
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        ObjectMapper objectMapper = new ObjectMapper();
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));

        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );

        SubmissionParityReport report = service.buildReport("2026-03-15-heavy4-vafpbaqjnhbk1mtw");

        System.out.println("FFLogs status: " + report.fflogs().status());
        System.out.println("Report code: " + report.fflogs().reportCode());
        System.out.println("Selected fight: " + report.fflogs().selectedFightName());
        System.out.println("Comparisons: " + report.comparisons().size());

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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        ObjectMapper objectMapper = new ObjectMapper();
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));

        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );

        SubmissionParityReport.SubmissionMetadata metadata = objectMapper.readValue(
                Files.readString(
                        Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "metadata.json"),
                        StandardCharsets.UTF_8
                ),
                SubmissionParityReport.SubmissionMetadata.class
        );
        Method buildFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "buildFflogsSummary",
                SubmissionParityReport.SubmissionMetadata.class
        );
        buildFflogsSummary.setAccessible(true);
        SubmissionParityReport.FflogsReportSummary summary =
                (SubmissionParityReport.FflogsReportSummary) buildFflogsSummary.invoke(service, metadata);

        Method deriveReplayWindow = SubmissionParityReportService.class.getDeclaredMethod(
                "deriveReplayWindow",
                SubmissionParityReport.FflogsReportSummary.class
        );
        deriveReplayWindow.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<?> replayWindow = (Optional<?>) deriveReplayWindow.invoke(service, summary);

        Method shouldIncludeLine = SubmissionParityReportService.class.getDeclaredMethod(
                "shouldIncludeLine",
                String.class,
                Optional.class
        );
        shouldIncludeLine.setAccessible(true);

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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        ObjectMapper objectMapper = new ObjectMapper();
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));

        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );

        SubmissionParityReport.SubmissionMetadata metadata = objectMapper.readValue(
                Files.readString(
                        Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "metadata.json"),
                        StandardCharsets.UTF_8
                ),
                SubmissionParityReport.SubmissionMetadata.class
        );

        Method buildFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "buildFflogsSummary",
                SubmissionParityReport.SubmissionMetadata.class
        );
        buildFflogsSummary.setAccessible(true);
        SubmissionParityReport.FflogsReportSummary summary =
                (SubmissionParityReport.FflogsReportSummary) buildFflogsSummary.invoke(service, metadata);

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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        ObjectMapper objectMapper = new ObjectMapper();
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));

        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );

        SubmissionParityReport.SubmissionMetadata metadata = objectMapper.readValue(
                Files.readString(
                        Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "metadata.json"),
                        StandardCharsets.UTF_8
                ),
                SubmissionParityReport.SubmissionMetadata.class
        );

        Method buildFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "buildFflogsSummary",
                SubmissionParityReport.SubmissionMetadata.class
        );
        buildFflogsSummary.setAccessible(true);
        SubmissionParityReport.FflogsReportSummary summary =
                (SubmissionParityReport.FflogsReportSummary) buildFflogsSummary.invoke(service, metadata);
        assertEquals(2, summary.selectedFightId());

        Method deriveReplayWindow = SubmissionParityReportService.class.getDeclaredMethod(
                "deriveReplayWindow",
                SubmissionParityReport.FflogsReportSummary.class
        );
        deriveReplayWindow.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<?> replayWindow = (Optional<?>) deriveReplayWindow.invoke(service, summary);

        Method shouldIncludeLine = SubmissionParityReportService.class.getDeclaredMethod(
                "shouldIncludeLine",
                String.class,
                Optional.class
        );
        shouldIncludeLine.setAccessible(true);

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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        ObjectMapper objectMapper = new ObjectMapper();
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pace.fflogs.partition"));

        ActLineParser parser = new ActLineParser();
        SubmissionParityReportService service = new SubmissionParityReportService(
                parser,
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );

        SubmissionParityReport.SubmissionMetadata metadata = objectMapper.readValue(
                Files.readString(
                        Path.of("data", "submissions", "2026-03-15-heavy4-vafpbaqjnhbk1mtw", "metadata.json"),
                        StandardCharsets.UTF_8
                ),
                SubmissionParityReport.SubmissionMetadata.class
        );

        Method buildFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "buildFflogsSummary",
                SubmissionParityReport.SubmissionMetadata.class
        );
        buildFflogsSummary.setAccessible(true);
        SubmissionParityReport.FflogsReportSummary summary =
                (SubmissionParityReport.FflogsReportSummary) buildFflogsSummary.invoke(service, metadata);
        assertEquals(2, summary.selectedFightId());

        Method deriveReplayWindow = SubmissionParityReportService.class.getDeclaredMethod(
                "deriveReplayWindow",
                SubmissionParityReport.FflogsReportSummary.class
        );
        deriveReplayWindow.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<?> replayWindow = (Optional<?>) deriveReplayWindow.invoke(service, summary);

        Method shouldIncludeLine = SubmissionParityReportService.class.getDeclaredMethod(
                "shouldIncludeLine",
                String.class,
                Optional.class
        );
        shouldIncludeLine.setAccessible(true);

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

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String envOrProperty(String envKey, String propertyKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return System.getProperty(propertyKey, "");
    }
}
