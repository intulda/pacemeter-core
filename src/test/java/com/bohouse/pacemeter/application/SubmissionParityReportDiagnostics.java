package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.BuffApplyRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.DotTickRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.DotStatusSignalRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.NetworkAbilityRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.PartyList;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.DamageType;
import com.bohouse.pacemeter.core.model.DotAttributionRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityReportDiagnostics {
    private static final Properties APPLICATION_YAML = loadApplicationYaml();
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
                .sorted((left, right) -> Double.compare(
                        Math.abs(right.rdpsDelta()),
                        Math.abs(left.rdpsDelta())
                ))
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
    void debugAllFightsParity_forHeavy2Report_printsFightByFightQuality() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport baseline = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", baseline.fflogs().status());

        List<SubmissionParityReport.FflogsFightSummary> fights = baseline.fflogs().fights().stream()
                .filter(fight -> fight.encounterId() > 0)
                .filter(fight -> fight.name() != null && !fight.name().isBlank())
                .toList();

        System.out.printf(
                "allFightsParity submission=%s reportCode=%s fightCount=%d%n",
                baseline.metadata().submissionId(),
                baseline.fflogs().reportCode(),
                fights.size()
        );

        for (SubmissionParityReport.FflogsFightSummary fight : fights) {
            SubmissionParityReport report = service.buildReportForFight(
                    "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                    fight.id()
            );
            SubmissionParityReport.ParityQualitySummary quality = report.parityQuality();
            System.out.printf(
                    "  fight=%d name=%s matched=%d mape=%.6f p95=%.6f max=%.6f outliers=%d within1=%.3f within3=%.3f within5=%.3f%n",
                    fight.id(),
                    fight.name(),
                    quality.matchedActorCount(),
                    quality.meanAbsolutePercentageError(),
                    quality.p95AbsolutePercentageError(),
                    quality.maxAbsolutePercentageError(),
                    quality.outlierActorCount(),
                    quality.withinOnePercentRatio(),
                    quality.withinThreePercentRatio(),
                    quality.withinFivePercentRatio()
            );
        }
    }

    @Test
    void debugAllFightsParity_forHeavy2Report_printsTopActorAndSkillDeltas() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport baseline = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", baseline.fflogs().status());

        List<SubmissionParityReport.FflogsFightSummary> fights = baseline.fflogs().fights().stream()
                .filter(fight -> fight.encounterId() > 0)
                .filter(fight -> fight.name() != null && !fight.name().isBlank())
                .toList();

        for (SubmissionParityReport.FflogsFightSummary fight : fights) {
            SubmissionParityReport report = service.buildReportForFight(
                    "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                    fight.id()
            );
            System.out.printf(
                    "allFightsTopDelta fight=%d name=%s p95=%.6f max=%.6f%n",
                    fight.id(),
                    fight.name(),
                    report.parityQuality().p95AbsolutePercentageError(),
                    report.parityQuality().maxAbsolutePercentageError()
            );

            report.comparisons().stream()
                    .sorted((left, right) -> Double.compare(
                            Math.abs(right.rdpsDelta()),
                            Math.abs(left.rdpsDelta())
                    ))
                    .limit(3)
                    .forEach(comparison -> {
                        System.out.printf(
                                "  actor=%s job=%s delta=%.1f ratio=%.4f totalDelta=%.1f warnings=%s%n",
                                comparison.localName(),
                                comparison.fflogsType(),
                                comparison.rdpsDelta(),
                                comparison.rdpsDeltaRatio(),
                                comparison.totalDamageDelta(),
                                comparison.warningReasons()
                        );
                        comparison.localTopSkills().stream().limit(5).forEach(skill -> System.out.printf(
                                "    localSkill guid=%s name=%s amount=%d hits=%d%n",
                                skill.skillGuid() == null ? "null" : Integer.toHexString(skill.skillGuid()).toUpperCase(),
                                skill.skillName(),
                                skill.totalDamage(),
                                skill.hitCount()
                        ));
                        comparison.fflogsTopSkills().stream().limit(5).forEach(skill -> System.out.printf(
                                "    fflogsSkill guid=%s name=%s amount=%d hits=%d%n",
                                skill.skillGuid() == null ? "null" : Integer.toHexString(skill.skillGuid()).toUpperCase(),
                                skill.skillName(),
                                skill.totalDamage(),
                                skill.hitCount()
                        ));
                    });
        }
    }

    @Test
    void debugDotAttributionModes_acrossSubmissions_printsCountsAndTopDeltas() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        List<String> submissions = List.of(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt"
        );

        for (String submissionId : submissions) {
            SubmissionParityReport report = service.buildReport(submissionId);
            assertEquals("ok", report.fflogs().status());
            Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
            Method shouldIncludeLine = openShouldIncludeLine();

            ActLineParser parser = new ActLineParser();
            CombatService combatService = new CombatService(
                    new CombatEngine(),
                    snapshot -> {},
                    (name, zone) -> Optional.empty(),
                    territoryId -> Optional.empty()
            );
            com.bohouse.pacemeter.application.port.inbound.CombatEventPort forwardingPort =
                    new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                        @Override
                        public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                                com.bohouse.pacemeter.core.event.CombatEvent event
                        ) {
                            return combatService.onEvent(event);
                        }

                        @Override
                        public void setCurrentPlayerId(ActorId playerId) {
                            combatService.setCurrentPlayerId(playerId);
                        }

                        @Override
                        public void setJobId(ActorId actorId, int jobId) {
                            combatService.setJobId(actorId, jobId);
                        }
                    };
            ActIngestionService ingestion = new ActIngestionService(
                    forwardingPort,
                    combatService,
                    new FflogsZoneLookup(new ObjectMapper())
            );

            Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
            for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
                boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
                if (!included) {
                    continue;
                }
                ParsedLine parsed = parser.parse(line);
                if (parsed != null) {
                    ingestion.onParsed(parsed);
                }
            }

            Map<String, Long> modeCounts = ingestion.debugDotAttributionModeCounts();
            String modeSummary = modeCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            System.out.printf(
                    "dotMode submission=%s selectedFight=%s modeCounts={%s}%n",
                    submissionId,
                    report.fflogs().selectedFightId(),
                    modeSummary
            );

            Map<String, Long> assignedAmounts = ingestion.debugDotAttributionAssignedAmounts();
            Map<String, Long> assignedHits = ingestion.debugDotAttributionAssignedHitCounts();
            assignedAmounts.entrySet().stream()
                    .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .limit(8)
                    .forEach(entry -> System.out.printf(
                            "  dotAssign key=%s amount=%d hits=%d%n",
                            entry.getKey(),
                            entry.getValue(),
                            assignedHits.getOrDefault(entry.getKey(), 0L)
                    ));

            report.comparisons().stream()
                    .sorted((left, right) -> Double.compare(Math.abs(right.rdpsDelta()), Math.abs(left.rdpsDelta())))
                    .limit(3)
                    .forEach(comparison -> System.out.printf(
                            "  topDelta actor=%s job=%s delta=%.1f ratio=%.3f warnings=%s%n",
                            comparison.localName(),
                            comparison.fflogsType(),
                            comparison.rdpsDelta(),
                            comparison.rdpsDeltaRatio(),
                            comparison.warningReasons()
                    ));
        }
    }

    @Test
    void debugStatus0ExpectedVsAssigned_bySourceAction_printsTopGaps() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        Method shouldIncludeLine = openShouldIncludeLine();
        DotAttributionRules rules = DotAttributionRules.fromCatalog();
        Map<Integer, Integer> statusToAction = rules.statusToAction();
        List<String> submissions = List.of(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt"
        );

        for (String submissionId : submissions) {
            SubmissionParityReport report = service.buildReport(submissionId);
            assertEquals("ok", report.fflogs().status());
            Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());

            ActLineParser parser = new ActLineParser();
            CombatService combatService = new CombatService(
                    new CombatEngine(),
                    snapshot -> {},
                    (name, zone) -> Optional.empty(),
                    territoryId -> Optional.empty()
            );
            com.bohouse.pacemeter.application.port.inbound.CombatEventPort forwardingPort =
                    new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                        @Override
                        public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                                com.bohouse.pacemeter.core.event.CombatEvent event
                        ) {
                            return combatService.onEvent(event);
                        }

                        @Override
                        public void setCurrentPlayerId(ActorId playerId) {
                            combatService.setCurrentPlayerId(playerId);
                        }

                        @Override
                        public void setJobId(ActorId actorId, int jobId) {
                            combatService.setJobId(actorId, jobId);
                        }
                    };
            ActIngestionService ingestion = new ActIngestionService(
                    forwardingPort,
                    combatService,
                    new FflogsZoneLookup(new ObjectMapper())
            );

            Set<Long> partyMembers = new HashSet<>();
            Map<SourceTargetKey, DotEvidence> actionEvidenceBySourceTarget = new HashMap<>();
            Map<SourceTargetKey, DotEvidence> statusEvidenceBySourceTarget = new HashMap<>();
            Map<SourceActionKey, Long> expectedBySourceAction = new HashMap<>();
            long totalStatus0KnownSourceDamage = 0L;
            long matchedExpectedDamage = 0L;

            Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
            for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
                boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
                if (!included) {
                    continue;
                }
                ParsedLine parsed = parser.parse(line);
                if (parsed == null) {
                    continue;
                }
                ingestion.onParsed(parsed);

                if (parsed instanceof PartyList partyList) {
                    partyMembers.clear();
                    partyMembers.addAll(partyList.partyMemberIds());
                    continue;
                }
                if (parsed instanceof NetworkAbilityRaw ability) {
                    if (partyMembers.contains(ability.actorId())) {
                        actionEvidenceBySourceTarget.put(
                                new SourceTargetKey(ability.actorId(), ability.targetId()),
                                new DotEvidence(ability.skillId(), ability.ts())
                        );
                    }
                    continue;
                }
                if (parsed instanceof BuffApplyRaw buffApply) {
                    if (partyMembers.contains(buffApply.sourceId())) {
                        statusEvidenceBySourceTarget.put(
                                new SourceTargetKey(buffApply.sourceId(), buffApply.targetId()),
                                new DotEvidence(buffApply.statusId(), buffApply.ts())
                        );
                    }
                    continue;
                }
                if (parsed instanceof DotStatusSignalRaw signal) {
                    for (DotStatusSignalRaw.StatusSignal statusSignal : signal.signals()) {
                        if (!partyMembers.contains(statusSignal.sourceId())) {
                            continue;
                        }
                        statusEvidenceBySourceTarget.put(
                                new SourceTargetKey(statusSignal.sourceId(), signal.targetId()),
                                new DotEvidence(statusSignal.statusId(), signal.ts())
                        );
                    }
                    continue;
                }
                if (!(parsed instanceof DotTickRaw dot)) {
                    continue;
                }
                if (dot.statusId() != 0 || dot.sourceId() == 0L || !partyMembers.contains(dot.sourceId())) {
                    continue;
                }

                totalStatus0KnownSourceDamage += dot.damage();
                SourceTargetKey key = new SourceTargetKey(dot.sourceId(), dot.targetId());
                DotEvidence actionEvidence = actionEvidenceBySourceTarget.get(key);
                DotEvidence statusEvidence = statusEvidenceBySourceTarget.get(key);
                Integer expectedAction = resolveExpectedActionFromExactEvidence(
                        dot.ts(),
                        actionEvidence,
                        statusEvidence,
                        statusToAction
                );
                if (expectedAction == null || expectedAction == 0) {
                    continue;
                }
                matchedExpectedDamage += dot.damage();
                expectedBySourceAction.merge(new SourceActionKey(dot.sourceId(), expectedAction), dot.damage(), Long::sum);
            }

            Map<SourceActionKey, Long> assignedBySourceAction = aggregateAssignedStatus0BySourceAction(
                    ingestion.debugDotAttributionAssignedAmounts()
            );
            System.out.printf(
                    "status0Expected submission=%s selectedFight=%s totalDamage=%d matchedDamage=%d matchedRatio=%.3f%n",
                    submissionId,
                    report.fflogs().selectedFightId(),
                    totalStatus0KnownSourceDamage,
                    matchedExpectedDamage,
                    totalStatus0KnownSourceDamage > 0
                            ? matchedExpectedDamage / (double) totalStatus0KnownSourceDamage
                            : 0.0
            );
            expectedBySourceAction.entrySet().stream()
                    .map(entry -> {
                        long assigned = assignedBySourceAction.getOrDefault(entry.getKey(), 0L);
                        long delta = assigned - entry.getValue();
                        return new SourceActionDelta(entry.getKey().sourceId(), entry.getKey().actionId(), entry.getValue(), assigned, delta);
                    })
                    .sorted((left, right) -> Long.compare(Math.abs(right.delta()), Math.abs(left.delta())))
                    .limit(10)
                    .forEach(delta -> System.out.printf(
                            "  expectedVsAssigned source=%s action=%s expected=%d assigned=%d delta=%d%n",
                            Long.toHexString(delta.sourceId()).toUpperCase(),
                            Integer.toHexString(delta.actionId()).toUpperCase(),
                            delta.expected(),
                            delta.assigned(),
                            delta.delta()
                    ));
        }
    }

    @Test
    void debugType37SignalCoverage_acrossSubmissions_printsSelectedFightCounts() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        Method shouldIncludeLine = openShouldIncludeLine();
        Set<Integer> trackedStatusIds = Set.of(0x0767, 0x0F2B, 0x0A38, 0x0A9F, 0x0907, 0x04CC, 0x04AB);
        List<String> submissions = List.of(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz"
        );

        for (String submissionId : submissions) {
            SubmissionParityReport report = service.buildReport(submissionId);
            assertEquals("ok", report.fflogs().status());
            Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
            assertTrue(replayWindow.isPresent());

            long type37Total = 0L;
            long type37WindowIncluded = 0L;
            long dotSignalParsedLines = 0L;
            long dotSignalParsedSlots = 0L;
            long trackedSignalSlots = 0L;
            Map<Integer, Long> trackedByStatus = new HashMap<>();

            Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
            for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
                if (line.startsWith("37|")) {
                    type37Total++;
                }
                boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
                if (!included) {
                    continue;
                }
                if (line.startsWith("37|")) {
                    type37WindowIncluded++;
                }
                ParsedLine parsed = new ActLineParser().parse(line);
                if (!(parsed instanceof DotStatusSignalRaw signal)) {
                    continue;
                }
                dotSignalParsedLines++;
                dotSignalParsedSlots += signal.signals().size();
                for (DotStatusSignalRaw.StatusSignal statusSignal : signal.signals()) {
                    if (!trackedStatusIds.contains(statusSignal.statusId())) {
                        continue;
                    }
                    trackedSignalSlots++;
                    trackedByStatus.merge(statusSignal.statusId(), 1L, Long::sum);
                }
            }

            String trackedSummary = trackedByStatus.entrySet().stream()
                    .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .map(entry -> Integer.toHexString(entry.getKey()).toUpperCase() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));

            System.out.printf(
                    "type37Coverage submission=%s selectedFight=%s total37=%d included37=%d parsedLines=%d parsedSlots=%d trackedSlots=%d trackedByStatus={%s}%n",
                    submissionId,
                    report.fflogs().selectedFightId(),
                    type37Total,
                    type37WindowIncluded,
                    dotSignalParsedLines,
                    dotSignalParsedSlots,
                    trackedSignalSlots,
                    trackedSummary
            );
        }
    }

    @Test
    void debugLindwurmFight8RainbowDripWindowBoundary_printsInclusion() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz");
        assertEquals("ok", report.fflogs().status());
        assertEquals(8, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        assertTrue(replayWindow.isPresent());
        Method shouldIncludeLine = openShouldIncludeLine();

        Object window = replayWindow.orElseThrow();
        Method fightStartMs = window.getClass().getDeclaredMethod("fightStartMs");
        Method fightEndMs = window.getClass().getDeclaredMethod("fightEndMs");
        Method startInclusiveMs = window.getClass().getDeclaredMethod("startInclusiveMs");
        Method endInclusiveMs = window.getClass().getDeclaredMethod("endInclusiveMs");
        fightStartMs.setAccessible(true);
        fightEndMs.setAccessible(true);
        startInclusiveMs.setAccessible(true);
        endInclusiveMs.setAccessible(true);

        long fightStart = (long) fightStartMs.invoke(window);
        long fightEnd = (long) fightEndMs.invoke(window);
        long startInclusive = (long) startInclusiveMs.invoke(window);
        long endInclusive = (long) endInclusiveMs.invoke(window);

        System.out.println("fightStart=" + Instant.ofEpochMilli(fightStart));
        System.out.println("fightEnd=" + Instant.ofEpochMilli(fightEnd));
        System.out.println("startInclusive=" + Instant.ofEpochMilli(startInclusive));
        System.out.println("endInclusive=" + Instant.ofEpochMilli(endInclusive));

        Path combatLog = Path.of("data", "submissions", "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz", "combat.log");
        long rainbowCount = 0L;
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            if (!line.startsWith("21|") || !line.contains("|8780|")) {
                continue;
            }
            rainbowCount++;
            String[] parts = line.split("\\|", 4);
            Instant ts = parseLineInstant(parts);
            long tsMs = ts == null ? -1L : ts.toEpochMilli();
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            System.out.printf(
                    "rainbow ts=%s included=%s relToFightEndMs=%d line=%s%n",
                    ts,
                    included,
                    tsMs - fightEnd,
                    line
            );
        }
        assertTrue(rainbowCount > 0);
    }

    @Test
    void debugLindwurmFight8RainbowDripFflogsEvents_printsHitList() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz");
        assertEquals("ok", report.fflogs().status());
        assertEquals(8, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison pctComparison = report.comparisons().stream()
                .filter(c -> "Pictomancer".equalsIgnoreCase(c.fflogsType()) && "이끼이끼".equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> events = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                pctComparison.fflogsActorId(),
                0x8780
        );

        long total = events.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        System.out.println("fflogs8780 eventCount=" + events.size() + " total=" + total);
        for (FflogsApiClient.DamageEventEntry event : events) {
            System.out.printf(
                    "fflogs8780 ts=%d source=%d target=%d ability=%04X amount=%d hitType=%s%n",
                    event.timestamp(),
                    event.sourceId(),
                    event.targetId(),
                    event.abilityGameId(),
                    event.amount(),
                    event.hitType()
            );
        }

        assertTrue(!events.isEmpty());
    }

    @Test
    void debugLindwurmFight8PetContributionByOwner_printsAggregatedPetBuffTotals() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz");
        assertEquals("ok", report.fflogs().status());
        assertEquals(8, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        assertTrue(replayWindow.isPresent());
        Method shouldIncludeLine = openShouldIncludeLine();

        CombatEngine engine = new CombatEngine();
        CombatService combatService = new CombatService(
                engine,
                snapshot -> { },
                (fightName, territoryId) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort forwardingPort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(com.bohouse.pacemeter.core.event.CombatEvent event) {
                        return combatService.onEvent(event);
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                        combatService.setCurrentPlayerId(playerId);
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                        combatService.setJobId(actorId, jobId);
                    }
                };
        ActIngestionService ingestion = new ActIngestionService(
                forwardingPort,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );
        ingestion.onParsed(new com.bohouse.pacemeter.adapter.inbound.actws.ZoneChanged(
                Instant.ofEpochMilli(0L),
                report.metadata().zoneId(),
                ""
        ));

        Path combatLog = Path.of("data", "submissions", "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = new ActLineParser().parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        com.bohouse.pacemeter.core.model.CombatState state = engine.currentState();
        Map<ActorId, PetContributionSummary> byOwner = new HashMap<>();
        for (Map.Entry<ActorId, ActorId> entry : state.ownerMap().entrySet()) {
            com.bohouse.pacemeter.core.model.ActorStats petStats = state.actors().get(entry.getKey());
            if (petStats == null) {
                continue;
            }
            PetContributionSummary current = byOwner.get(entry.getValue());
            PetContributionSummary updated = current == null
                    ? new PetContributionSummary(1, petStats.totalDamage(), petStats.totalGrantedBuffContribution(), petStats.totalReceivedBuffContribution())
                    : new PetContributionSummary(
                            current.petCount() + 1,
                            current.totalDamage() + petStats.totalDamage(),
                            current.granted() + petStats.totalGrantedBuffContribution(),
                            current.received() + petStats.totalReceivedBuffContribution()
                    );
            byOwner.put(entry.getValue(), updated);
        }

        byOwner.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue().granted(), left.getValue().granted()))
                .forEach(entry -> {
                    com.bohouse.pacemeter.core.model.ActorStats ownerStats = state.actors().get(entry.getKey());
                    String ownerName = ownerStats == null ? Long.toHexString(entry.getKey().value()) : ownerStats.name();
                    PetContributionSummary summary = entry.getValue();
                    System.out.printf(
                            "lindwurm.petAgg owner=%s petCount=%d petDamage=%d petGranted=%.1f petReceived=%.1f%n",
                            ownerName,
                            summary.petCount(),
                            summary.totalDamage(),
                            summary.granted(),
                            summary.received()
                    );
                });

        assertTrue(!byOwner.isEmpty());
    }

    @Test
    void debugHeavy2Fight6Parity_withConfiguredFflogsCredentials_printsActorDelta() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");

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
    void debugHeavy2Fight6UnknownSourceDotDiagnostics_printsWindowCounts() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertNotNull(report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();
        Object window = replayWindow.orElseThrow();
        Method fightStartMs = window.getClass().getDeclaredMethod("fightStartMs");
        Method fightEndMs = window.getClass().getDeclaredMethod("fightEndMs");
        fightStartMs.setAccessible(true);
        fightEndMs.setAccessible(true);
        long windowFightStartMs = (long) fightStartMs.invoke(window);
        long windowFightEndMs = (long) fightEndMs.invoke(window);

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

        long totalDot = 0L;
        long totalDotUnknownSource = 0L;
        long acceptedDotUnknownSource = 0L;
        long totalWhmDot = 0L;
        long acceptedWhmDot = 0L;
        long totalWhmDiaApplicationsInFile = 0L;
        long includedWhmDiaApplications = 0L;
        long totalWhmDiaDotInFile = 0L;
        long includedWhmDiaDot = 0L;
        long whmDiaDotBeforeWindow = 0L;
        long whmDiaDotAfterWindow = 0L;
        Map<String, Long> rejectedWhmDotByTarget = new HashMap<>();
        List<String> rejectedWhmDotSamples = new ArrayList<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|", -1);
            if (parts.length > 6 && "21".equals(parts[0]) && "102884E5".equalsIgnoreCase(parts[2]) && "4094".equalsIgnoreCase(parts[4])) {
                totalWhmDiaApplicationsInFile++;
            }
            if (parts.length > 18 && "24".equals(parts[0]) && "DoT".equals(parts[4]) && "102884E5".equalsIgnoreCase(parts[17])) {
                totalWhmDiaDotInFile++;
                try {
                    long ts = Instant.parse(parts[1]).toEpochMilli();
                    if (ts < windowFightStartMs) {
                        whmDiaDotBeforeWindow++;
                    } else if (ts > windowFightEndMs) {
                        whmDiaDotAfterWindow++;
                    }
                } catch (Exception ignored) {
                }
            }

            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            if (parts.length > 6 && "21".equals(parts[0]) && "102884E5".equalsIgnoreCase(parts[2]) && "4094".equalsIgnoreCase(parts[4])) {
                includedWhmDiaApplications++;
            }
            if (parts.length > 18 && "24".equals(parts[0]) && "DoT".equals(parts[4]) && "102884E5".equalsIgnoreCase(parts[17])) {
                includedWhmDiaDot++;
            }
            ParsedLine parsed = parser.parse(line);
            if (!(parsed instanceof DotTickRaw dot) || !dot.isDot() || dot.damage() <= 0) {
                if (parsed != null) {
                    ingestion.onParsed(parsed);
                }
                continue;
            }

            totalDot++;
            boolean accepted = ingestion.wouldEmitDotDamage(dot);
            if (dot.sourceId() == 0L || dot.sourceId() == 0xE0000000L) {
                totalDotUnknownSource++;
                if (accepted) {
                    acceptedDotUnknownSource++;
                }
            }
            if (dot.sourceName().equals("백미도사") || dot.sourceId() == 0x102884E5L) {
                totalWhmDot++;
                if (accepted) {
                    acceptedWhmDot++;
                } else {
                    rejectedWhmDotByTarget.merge(dot.targetName(), 1L, Long::sum);
                    if (rejectedWhmDotSamples.size() < 10) {
                        rejectedWhmDotSamples.add(String.format(
                                "ts=%s target=%s(%s) status=%s source=%s(%s) damage=%d",
                                dot.ts(),
                                dot.targetName(),
                                Long.toHexString(dot.targetId()).toUpperCase(),
                                Integer.toHexString(dot.statusId()).toUpperCase(),
                                dot.sourceName(),
                                Long.toHexString(dot.sourceId()).toUpperCase(),
                                dot.damage()
                        ));
                    }
                }
            }

            ingestion.onParsed(dot);
        }

        System.out.printf(
                "fight6Dot total=%d unknownSource=%d unknownSourceAccepted=%d whmDot=%d whmDotAccepted=%d%n",
                totalDot,
                totalDotUnknownSource,
                acceptedDotUnknownSource,
                totalWhmDot,
                acceptedWhmDot
        );
        System.out.printf(
                "fight6WhmDia file21=%d included21=%d file24=%d included24=%d%n",
                totalWhmDiaApplicationsInFile,
                includedWhmDiaApplications,
                totalWhmDiaDotInFile,
                includedWhmDiaDot
        );
        System.out.printf(
                "fight6WhmDiaWindow windowStart=%s windowEnd=%s dotBefore=%d dotAfter=%d%n",
                Instant.ofEpochMilli(windowFightStartMs),
                Instant.ofEpochMilli(windowFightEndMs),
                whmDiaDotBeforeWindow,
                whmDiaDotAfterWindow
        );
        System.out.println("fight6WhmRejectedTargets=" + rejectedWhmDotByTarget);
        System.out.println("fight6WhmRejectedSamples=" + rejectedWhmDotSamples);
    }

    @Test
    void debugHeavy2Fight6FflogsAbilityEventParity_printsWhmSchHitCounts() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertNotNull(report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison whm = report.comparisons().stream()
                .filter(c -> "WhiteMage".equalsIgnoreCase(c.fflogsType()))
                .findFirst()
                .orElseThrow();
        SubmissionParityReport.ActorParityComparison sch = report.comparisons().stream()
                .filter(c -> "Scholar".equalsIgnoreCase(c.fflogsType()) && "젤리".equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> whmDiaEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                whm.fflogsActorId(),
                0x4094
        );
        List<FflogsApiClient.DamageEventEntry> whmDiaStatus777Events = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                whm.fflogsActorId(),
                0x0777
        );
        List<FflogsApiClient.DamageEventEntry> whmDiaDotEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                whm.fflogsActorId(),
                0x074F
        );
        List<FflogsApiClient.DamageEventEntry> schBioEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                sch.fflogsActorId(),
                0x409C
        );
        List<FflogsApiClient.DamageEventEntry> schBioDotEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                sch.fflogsActorId(),
                0x0767
        );
        List<FflogsApiClient.DamageEventEntry> schBanefulEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                sch.fflogsActorId(),
                0x9094
        );
        List<FflogsApiClient.DamageEventEntry> schBanefulDotEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                sch.fflogsActorId(),
                0x0F2B
        );

        long whmDiaTotal = whmDiaEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long whmDiaStatus777Total = whmDiaStatus777Events.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long whmDiaDotTotal = whmDiaDotEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long schBioTotal = schBioEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long schBioDotTotal = schBioDotEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long schBanefulTotal = schBanefulEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        long schBanefulDotTotal = schBanefulDotEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();

        System.out.printf(
                "fight6FFLogsEvents WHM4094 count=%d total=%d | WHM0777 count=%d total=%d | WHM074F count=%d total=%d | SCH409C count=%d total=%d | SCH0767 count=%d total=%d | SCH9094 count=%d total=%d | SCH0F2B count=%d total=%d%n",
                whmDiaEvents.size(), whmDiaTotal,
                whmDiaStatus777Events.size(), whmDiaStatus777Total,
                whmDiaDotEvents.size(), whmDiaDotTotal,
                schBioEvents.size(), schBioTotal,
                schBioDotEvents.size(), schBioDotTotal,
                schBanefulEvents.size(), schBanefulTotal,
                schBanefulDotEvents.size(), schBanefulDotTotal
        );

        // 로컬 skill breakdown(이미 selected fight window replay 결과)
        report.comparisons().stream()
                .filter(c -> c.localName().equals("백미도사") || c.localName().equals("젤리"))
                .forEach(c -> System.out.println(
                        "fight6LocalTopSkills " + c.localName() + " -> " + c.localTopSkills()
                ));
    }

    @Test
    void debugHeavy2Fight6FflogsAbilityGuids_printsWhmSchTopAbilities() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertNotNull(report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison whm = report.comparisons().stream()
                .filter(c -> "WhiteMage".equalsIgnoreCase(c.fflogsType()))
                .findFirst()
                .orElseThrow();
        SubmissionParityReport.ActorParityComparison sch = report.comparisons().stream()
                .filter(c -> "Scholar".equalsIgnoreCase(c.fflogsType()) && "젤리".equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.AbilityDamageEntry> whmAbilities = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                whm.fflogsActorId()
        );
        List<FflogsApiClient.AbilityDamageEntry> schAbilities = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                sch.fflogsActorId()
        );

        System.out.println("fight6WHMAbilities:");
        whmAbilities.stream()
                .sorted((a, b) -> Double.compare(b.total(), a.total()))
                .limit(20)
                .forEach(entry -> System.out.printf(
                        "  name=%s guid=%s total=%.0f type=%s%n",
                        entry.name(),
                        entry.guid(),
                        entry.total(),
                        entry.type()
                ));

        System.out.println("fight6SCHAbilities:");
        schAbilities.stream()
                .sorted((a, b) -> Double.compare(b.total(), a.total()))
                .limit(20)
                .forEach(entry -> System.out.printf(
                        "  name=%s guid=%s total=%.0f type=%s%n",
                        entry.name(),
                        entry.guid(),
                        entry.total(),
                        entry.type()
                ));
    }

    @Test
    void debugHeavy2Fight6FflogsStatusAndActionPairs_printsSamWhmSchPairs() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Map<String, Integer[]> targets = Map.of(
                "WhiteMage", new Integer[]{0x4094, 0x074F},
                "Scholar", new Integer[]{0x409C, 0x0767, 0x9094, 0x0F2B},
                "Samurai", new Integer[]{0x1D41, 0x04CC}
        );

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (SubmissionParityReport.ActorParityComparison comparison : report.comparisons()) {
            Integer[] guids = targets.get(comparison.fflogsType());
            if (guids == null) {
                continue;
            }
            List<FflogsApiClient.AbilityDamageEntry> abilities = apiClient.fetchDamageDoneAbilities(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    comparison.fflogsActorId()
            );
            System.out.printf(
                    "heavy2.fflogsPairs actor=%s job=%s matched=%s%n",
                    comparison.localName(),
                    comparison.fflogsType(),
                    abilities.stream()
                            .filter(a -> a.guid() != null && java.util.Arrays.asList(guids).contains(a.guid()))
                            .sorted((a, b) -> Integer.compare(a.guid(), b.guid()))
                            .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                            .toList()
            );
        }
    }

    @Test
    void debugHeavy2Fight6RawDotWindow_printsSamWhmSchDotBuckets() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        Map<Long, String> targetActors = Map.of(
                0x102B1904L, "Samurai",
                0x102884E5L, "WhiteMage",
                0x1029CA55L, "Scholar"
        );

        Map<String, long[]> buckets = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (!(parsed instanceof DotTickRaw dot) || !dot.isDot()) {
                continue;
            }
            String actorJob = targetActors.get(dot.sourceId());
            if (actorJob == null) {
                continue;
            }
            String key = actorJob
                    + " status=" + formatGuid(dot.statusId())
                    + " target=" + dot.targetName()
                    + "(" + Long.toHexString(dot.targetId()).toUpperCase() + ")";
            long[] totals = buckets.computeIfAbsent(key, ignored -> new long[2]);
            totals[0] += 1;
            totals[1] += dot.damage();
        }

        buckets.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(entry -> System.out.printf(
                        "heavy2.rawDotBucket %s count=%d total=%d%n",
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1]
                ));
    }

    @Test
    void debugHeavy2Fight6EmittedDotBuckets_printsSamWhmSchActionTargets() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        Map<String, Integer> targetGuidByActor = Map.of(
                "재탄", 0x1D41,
                "젤리", 0x409C,
                "백미도사", 0x4094
        );

        Map<String, long[]> buckets = new HashMap<>();
        for (com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent event : capturedDamageEvents) {
            Integer targetActionId = targetGuidByActor.get(event.sourceName());
            if (targetActionId == null || targetActionId != event.actionId()) {
                continue;
            }
            if (event.damageType() != DamageType.DOT) {
                continue;
            }
            String key = event.sourceName()
                    + " action=" + formatGuid(event.actionId())
                    + " target=" + Long.toHexString(event.targetId().value()).toUpperCase();
            long[] totals = buckets.computeIfAbsent(key, ignored -> new long[2]);
            totals[0] += 1;
            totals[1] += event.amount();
        }

        buckets.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(entry -> System.out.printf(
                        "heavy2.emittedDotBucket %s count=%d total=%d%n",
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1]
                ));
    }

    @Test
    void debugHeavy2Fight6FflogsFightsAndDiaTotals_printsAbsoluteWindows() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());

        SubmissionParityReport.ActorParityComparison whm = report.comparisons().stream()
                .filter(c -> "WhiteMage".equalsIgnoreCase(c.fflogsType()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        System.out.println("reportStart=" + Instant.ofEpochMilli(report.fflogs().reportStartTime()));
        for (SubmissionParityReport.FflogsFightSummary fight : report.fflogs().fights()) {
            long absStart = report.fflogs().reportStartTime() + fight.startTime();
            long absEnd = report.fflogs().reportStartTime() + fight.endTime();
            List<FflogsApiClient.AbilityDamageEntry> abilities = apiClient.fetchDamageDoneAbilities(
                    report.fflogs().reportCode(),
                    fight.id(),
                    whm.fflogsActorId()
            );
            double diaTotal = abilities.stream()
                    .filter(a -> "Dia".equalsIgnoreCase(a.name()) || Integer.valueOf(0x4094).equals(a.guid()))
                    .mapToDouble(FflogsApiClient.AbilityDamageEntry::total)
                    .sum();
            System.out.printf(
                    "fight id=%d name=%s encounter=%d kill=%s absStart=%s absEnd=%s diaTotal=%.0f%n",
                    fight.id(),
                    fight.name(),
                    fight.encounterId(),
                    fight.kill(),
                    Instant.ofEpochMilli(absStart),
                    Instant.ofEpochMilli(absEnd),
                    diaTotal
            );
        }
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
    void debugStatus0DotEvidenceCoverage_acrossSubmissions_printsTargetMismatchRates() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        Method shouldIncludeLine = openShouldIncludeLine();
        Set<Integer> trackedStatusIds = DotAttributionRules.fromCatalog().snapshotStatusIds();
        Duration evidenceWindow = Duration.ofMillis(90_000L);
        List<String> submissionIds = List.of(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt"
        );

        for (String submissionId : submissionIds) {
            SubmissionParityReport report = service.buildReport(submissionId);
            Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());

            Map<SourceTargetKey, RecentDotEvidence> evidenceBySourceTarget = new HashMap<>();
            Map<Long, RecentDotEvidence> evidenceBySource = new HashMap<>();
            Set<Long> partyMembers = new HashSet<>();
            long status0KnownSourceDots = 0L;
            long exactEvidenceMatch = 0L;
            long sourceOnlyEvidenceMatch = 0L;
            long sourceOnlyTargetMismatch = 0L;
            long sourceOnlySameTargetName = 0L;
            long noRecentEvidence = 0L;

            Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
            for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
                boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
                if (!included) {
                    continue;
                }
                ParsedLine parsed = new ActLineParser().parse(line);
                if (parsed == null) {
                    continue;
                }
                if (parsed instanceof PartyList partyList) {
                    partyMembers.clear();
                    partyMembers.addAll(partyList.partyMemberIds());
                    continue;
                }
                if (parsed instanceof BuffApplyRaw buffApply) {
                    if (trackedStatusIds.contains(buffApply.statusId()) && partyMembers.contains(buffApply.sourceId())) {
                        RecentDotEvidence evidence = new RecentDotEvidence(
                                buffApply.ts(),
                                buffApply.targetId(),
                                buffApply.statusId(),
                                buffApply.targetName()
                        );
                        evidenceBySourceTarget.put(new SourceTargetKey(buffApply.sourceId(), buffApply.targetId()), evidence);
                        evidenceBySource.put(buffApply.sourceId(), evidence);
                    }
                    continue;
                }
                if (parsed instanceof DotStatusSignalRaw signal) {
                    for (DotStatusSignalRaw.StatusSignal statusSignal : signal.signals()) {
                        if (!trackedStatusIds.contains(statusSignal.statusId()) || !partyMembers.contains(statusSignal.sourceId())) {
                            continue;
                        }
                        RecentDotEvidence evidence = new RecentDotEvidence(
                                signal.ts(),
                                signal.targetId(),
                                statusSignal.statusId(),
                                ""
                        );
                        evidenceBySourceTarget.put(new SourceTargetKey(statusSignal.sourceId(), signal.targetId()), evidence);
                        evidenceBySource.put(statusSignal.sourceId(), evidence);
                    }
                    continue;
                }
                if (!(parsed instanceof DotTickRaw dot)) {
                    continue;
                }
                if (dot.statusId() != 0 || dot.sourceId() == 0L || !partyMembers.contains(dot.sourceId())) {
                    continue;
                }

                status0KnownSourceDots++;
                SourceTargetKey key = new SourceTargetKey(dot.sourceId(), dot.targetId());
                RecentDotEvidence exact = evidenceBySourceTarget.get(key);
                if (isRecent(dot.ts(), exact, evidenceWindow)) {
                    exactEvidenceMatch++;
                    continue;
                }
                RecentDotEvidence sourceOnly = evidenceBySource.get(dot.sourceId());
                if (isRecent(dot.ts(), sourceOnly, evidenceWindow)) {
                    sourceOnlyEvidenceMatch++;
                    if (sourceOnly != null && sourceOnly.targetId() != dot.targetId()) {
                        sourceOnlyTargetMismatch++;
                        if (normalizeName(sourceOnly.targetName()).equals(normalizeName(dot.targetName()))) {
                            sourceOnlySameTargetName++;
                        }
                    }
                } else {
                    noRecentEvidence++;
                }
            }

            System.out.printf(
                    "status0Evidence submission=%s selectedFight=%s total=%d exact=%d sourceOnly=%d sourceOnlyTargetMismatch=%d sourceOnlySameTargetName=%d noEvidence=%d%n",
                    submissionId,
                    report.fflogs().selectedFightId(),
                    status0KnownSourceDots,
                    exactEvidenceMatch,
                    sourceOnlyEvidenceMatch,
                    sourceOnlyTargetMismatch,
                    sourceOnlySameTargetName,
                    noRecentEvidence
            );
        }
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

    @Test
    void debugHeavy2Fight6WorstActorLineTypeEvidence_printsUnknownSkillCorrelations() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Set<String> targetJobs = Set.of("Samurai", "Scholar", "WhiteMage", "Pictomancer");
        List<String> targetActors = report.comparisons().stream()
                .filter(c -> targetJobs.contains(c.fflogsType()))
                .sorted((a, b) -> Double.compare(Math.abs(b.rdpsDeltaRatio()), Math.abs(a.rdpsDeltaRatio())))
                .limit(4)
                .map(SubmissionParityReport.ActorParityComparison::localName)
                .toList();
        assertTrue(!targetActors.isEmpty());

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

        List<ParsedReplayLine> includedLines = new ArrayList<>();
        List<UnknownEvent> unknownEvents = new ArrayList<>();
        Map<String, Map<String, Long>> actorSourceTypeCounts = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");

        int includedIndex = 0;
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            String[] parts = line.split("\\|", -1);
            String type = parts.length > 0 ? parts[0] : "";
            Instant ts = parseLineInstant(parts);
            includedLines.add(new ParsedReplayLine(includedIndex, ts, type, line, parsed));

            long sourceId = extractSourceId(type, parts);
            if (sourceId != 0L) {
                String actorName = actorNamesById.get(sourceId);
                if (actorName != null && targetActors.contains(actorName)) {
                    actorSourceTypeCounts.computeIfAbsent(actorName, ignored -> new HashMap<>())
                            .merge(type, 1L, Long::sum);
                }
            }

            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.damage() > 0
                    && ingestion.wouldEmitDamage(ability)) {
                String actorName = actorNamesById.getOrDefault(ability.actorId(), ability.actorName());
                if (targetActors.contains(actorName)) {
                    String resolved = skillKey(ability.skillName(), ability.skillId());
                    if (isUnknownSkillName(resolved)) {
                        unknownEvents.add(new UnknownEvent(actorName, resolved, ability.damage(), includedIndex));
                    }
                }
            } else if (parsed instanceof DotTickRaw dot
                    && dot.damage() > 0
                    && dot.isDot()
                    && ingestion.wouldEmitDotDamage(dot)) {
                String actorName = actorNamesById.getOrDefault(dot.sourceId(), dot.sourceName());
                if (targetActors.contains(actorName)) {
                    String resolved = "DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase();
                    if (isUnknownSkillName(resolved)) {
                        unknownEvents.add(new UnknownEvent(actorName, resolved, dot.damage(), includedIndex));
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
            includedIndex++;
        }

        Map<String, List<UnknownEvent>> unknownByActor = new HashMap<>();
        for (UnknownEvent event : unknownEvents) {
            unknownByActor.computeIfAbsent(event.actorName(), ignored -> new ArrayList<>()).add(event);
        }

        List<String> interestingTypes = List.of("20", "37", "38", "39", "261", "264", "270");
        System.out.println("heavy2.targetActors=" + targetActors);
        for (String actorName : targetActors) {
            List<UnknownEvent> actorUnknownEvents = unknownByActor.getOrDefault(actorName, List.of());
            Map<String, Long> nearbyTypeCounts = new HashMap<>();
            for (UnknownEvent event : actorUnknownEvents) {
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
                }
            }

            long unknownDamage = actorUnknownEvents.stream().mapToLong(UnknownEvent::damage).sum();
            System.out.printf(
                    "heavy2.actor=%s sourceTypes=%s unknownEvents=%d unknownDamage=%d unknownNearbyTypes=%s%n",
                    actorName,
                    actorSourceTypeCounts.getOrDefault(actorName, Map.of()),
                    actorUnknownEvents.size(),
                    unknownDamage,
                    nearbyTypeCounts
            );
        }
    }

    @Test
    void debugHeavy2Fight6PctUnknownEvents_printsRawToFflogsCandidates() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison pct = report.comparisons().stream()
                .filter(c -> "Pictomancer".equalsIgnoreCase(c.fflogsType()) && "바나바나".equals(c.localName()))
                .findFirst()
                .orElseThrow();

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

        List<ParsedReplayLine> includedLines = new ArrayList<>();
        List<UnknownEvent> pctUnknownEvents = new ArrayList<>();
        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        int includedIndex = 0;
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            String[] parts = line.split("\\|", -1);
            String type = parts.length > 0 ? parts[0] : "";
            Instant ts = parseLineInstant(parts);
            includedLines.add(new ParsedReplayLine(includedIndex, ts, type, line, parsed));

            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.damage() > 0
                    && ingestion.wouldEmitDamage(ability)) {
                String actorName = actorNamesById.getOrDefault(ability.actorId(), ability.actorName());
                if ("바나바나".equals(actorName)) {
                    String skill = skillKey(ability.skillName(), ability.skillId());
                    if (isUnknownSkillName(skill)) {
                        pctUnknownEvents.add(new UnknownEvent(actorName, skill, ability.damage(), includedIndex));
                    }
                }
            } else if (parsed instanceof DotTickRaw dot
                    && dot.damage() > 0
                    && dot.isDot()
                    && ingestion.wouldEmitDotDamage(dot)) {
                String actorName = actorNamesById.getOrDefault(dot.sourceId(), dot.sourceName());
                if ("바나바나".equals(actorName)) {
                    String skill = "DoT#" + Integer.toHexString(ingestion.resolveDotActionId(dot)).toUpperCase();
                    if (isUnknownSkillName(skill)) {
                        pctUnknownEvents.add(new UnknownEvent(actorName, skill, dot.damage(), includedIndex));
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
            includedIndex++;
        }

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.AbilityDamageEntry> fflogsAbilities = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                pct.fflogsActorId()
        );

        System.out.println("heavy2.pctUnknownEventCount=" + pctUnknownEvents.size());
        System.out.println("heavy2.pctLocalTopSkills=" + pct.localTopSkills());
        System.out.println("heavy2.pctFflogsTopSkills=" + pct.fflogsTopSkills());
        System.out.println("heavy2.pctFflogsAbilitiesTop20="
                + fflogsAbilities.stream()
                .sorted((a, b) -> Double.compare(b.total(), a.total()))
                .limit(20)
                .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                .toList());

        for (UnknownEvent event : pctUnknownEvents) {
            ParsedReplayLine raw = includedLines.get(event.lineIndex());
            List<String> neighbors = new ArrayList<>();
            for (int offset = -4; offset <= 4; offset++) {
                if (offset == 0) {
                    continue;
                }
                int idx = event.lineIndex() + offset;
                if (idx < 0 || idx >= includedLines.size()) {
                    continue;
                }
                ParsedReplayLine neighbor = includedLines.get(idx);
                if (List.of("20", "37", "38", "39", "261", "264", "270").contains(neighbor.type())) {
                    neighbors.add("type=" + neighbor.type() + " ts=" + neighbor.timestamp());
                }
            }
            Integer localSkillId = extractLocalSkillId(event.skillName());
            List<String> matchedFflogsByGuid = localSkillId == null
                    ? List.of()
                    : fflogsAbilities.stream()
                    .filter(a -> a.guid() != null && a.guid().equals(localSkillId))
                    .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                    .toList();

            System.out.printf(
                    "heavy2.pctUnknown skill=%s damage=%d lineType=%s ts=%s localSkillId=%s matchedFflogsByGuid=%s%n",
                    event.skillName(),
                    event.damage(),
                    raw.type(),
                    raw.timestamp(),
                    localSkillId == null ? "null" : formatGuid(localSkillId),
                    matchedFflogsByGuid
            );
            System.out.println("  raw=" + raw.rawLine());
            System.out.println("  nearbyInterestingTypes=" + neighbors);
        }

        assertTrue(!pctUnknownEvents.isEmpty());
    }

    @Test
    void debugHeavy2Fight6WorstActorGuidSkillDelta_printsActionLevelMismatch() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Set<String> targetJobs = Set.of("Samurai", "Scholar", "WhiteMage", "Pictomancer");
        List<SubmissionParityReport.ActorParityComparison> targets = report.comparisons().stream()
                .filter(c -> targetJobs.contains(c.fflogsType()))
                .sorted((a, b) -> Double.compare(Math.abs(b.rdpsDeltaRatio()), Math.abs(a.rdpsDeltaRatio())))
                .limit(4)
                .toList();
        assertTrue(!targets.isEmpty());

        FflogsApiClient apiClient = buildConfiguredApiClient();
        Map<String, CombatDebugSnapshot.ActorDebugEntry> combatByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : report.combat().actors()) {
            combatByName.put(actor.name(), actor);
        }
        Map<ActorId, List<CombatDebugSnapshot.SkillDebugEntry>> skillsByActorId = new HashMap<>();
        for (CombatDebugSnapshot.ActorSkillBreakdown breakdown : report.combat().skillBreakdowns()) {
            skillsByActorId.put(breakdown.actorId(), breakdown.skills());
        }

        for (SubmissionParityReport.ActorParityComparison actor : targets) {
            List<FflogsApiClient.AbilityDamageEntry> abilities = apiClient.fetchDamageDoneAbilities(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    actor.fflogsActorId()
            );

            Map<Integer, FflogsApiClient.AbilityDamageEntry> fflogsByGuid = new HashMap<>();
            for (FflogsApiClient.AbilityDamageEntry ability : abilities) {
                if (ability.guid() != null) {
                    fflogsByGuid.put(ability.guid(), ability);
                }
            }

            CombatDebugSnapshot.ActorDebugEntry combatActor = combatByName.get(actor.localName());
            List<CombatDebugSnapshot.SkillDebugEntry> fullLocalSkills = combatActor == null
                    ? List.of()
                    : skillsByActorId.getOrDefault(combatActor.actorId(), List.of());

            System.out.printf(
                    "heavy2.guidDelta actor=%s job=%s rdpsDelta=%.1f ratio=%.3f%n",
                    actor.localName(),
                    actor.fflogsType(),
                    actor.rdpsDelta(),
                    actor.rdpsDeltaRatio()
            );

            Map<Integer, Long> localByGuid = new HashMap<>();
            for (CombatDebugSnapshot.SkillDebugEntry localSkill : fullLocalSkills) {
                Integer localSkillId = extractLocalSkillId(localSkill.skillName());
                if (localSkillId == null || localSkillId <= 0) {
                    continue;
                }
                localByGuid.merge(localSkillId, localSkill.totalDamage(), Long::sum);
            }

            localByGuid.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> {
                Integer localSkillId = entry.getKey();
                long localTotal = entry.getValue();
                FflogsApiClient.AbilityDamageEntry matched = fflogsByGuid.get(localSkillId);
                long fflogsTotal = matched == null ? 0L : Math.round(matched.total());
                long delta = localTotal - fflogsTotal;
                System.out.printf(
                        "  guid=%s local=%d fflogs=%d delta=%d%n",
                        formatGuid(localSkillId),
                        localTotal,
                        fflogsTotal,
                        delta
                );
            });

            Set<Integer> localIds = localByGuid.keySet();
            List<String> missingHigh = abilities.stream()
                    .filter(a -> a.guid() != null && !localIds.contains(a.guid()))
                    .sorted((a, b) -> Double.compare(b.total(), a.total()))
                    .limit(10)
                    .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                    .toList();
            System.out.println("  fflogsMissingInLocalTop=" + missingHigh);
        }
    }

    @Test
    void debugHeavy2Fight6GuidParityFromIngestion_printsEmitVsFflogsTotals() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        Map<String, Integer> targetGuidByActor = Map.of(
                "재탄", 0x1D41,
                "젤리", 0x409C,
                "백미도사", 0x4094,
                "바나바나", 0x8780
        );

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (Map.Entry<String, Integer> entry : targetGuidByActor.entrySet()) {
            String actorName = entry.getKey();
            int guid = entry.getValue();

            long localTotal = capturedDamageEvents.stream()
                    .filter(e -> actorName.equals(e.sourceName()) && e.actionId() == guid)
                    .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    .sum();
            long localHits = capturedDamageEvents.stream()
                    .filter(e -> actorName.equals(e.sourceName()) && e.actionId() == guid)
                    .count();

            SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                    .filter(c -> actorName.equals(c.localName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(comparison);
            List<FflogsApiClient.AbilityDamageEntry> fflogsAbilities = apiClient.fetchDamageDoneAbilities(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    comparison.fflogsActorId()
            );
            long fflogsAbilityTotal = fflogsAbilities.stream()
                    .filter(a -> a.guid() != null && a.guid() == guid)
                    .mapToLong(a -> Math.round(a.total()))
                    .sum();
            List<FflogsApiClient.DamageEventEntry> fflogsEvents = apiClient.fetchDamageDoneEventsByAbility(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    comparison.fflogsActorId(),
                    guid
            );
            long fflogsTotal = fflogsEvents.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();

            System.out.printf(
                    "heavy2.emitVsFflogs actor=%s guid=%s localTotal=%d localHits=%d fflogsEventTotal=%d fflogsEventHits=%d fflogsAbilityTotal=%d deltaVsAbility=%d%n",
                    actorName,
                    formatGuid(guid),
                    localTotal,
                    localHits,
                    fflogsTotal,
                    fflogsEvents.size(),
                    fflogsAbilityTotal,
                    localTotal - fflogsAbilityTotal
            );
        }
    }

    @Test
    void debugHeavy2Fight6ActorAbilityTargetParity_printsSamSchWhmTargetDeltas() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        Map<Long, String> targetNameById = new HashMap<>();
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        Map<String, SubmissionParityReport.ActorParityComparison> actors = new HashMap<>();
        for (SubmissionParityReport.ActorParityComparison comparison : report.comparisons()) {
            if (Set.of("재탄", "젤리", "백미도사").contains(comparison.localName())) {
                actors.put(comparison.localName(), comparison);
            }
        }
        assertEquals(3, actors.size());
        Map<String, Long> localActorIdByName = report.combat().actors().stream()
                .collect(Collectors.toMap(
                        CombatDebugSnapshot.ActorDebugEntry::name,
                        actor -> actor.actorId().value(),
                        (left, right) -> left
                ));

        Map<String, Integer> primaryAbilityByActor = Map.of(
                "재탄", 0x1D41,
                "젤리", 0x409C,
                "백미도사", 0x4094
        );
        Map<String, Integer> fallbackAbilityByActor = Map.of(
                "재탄", 0x4CC,
                "젤리", 0x767,
                "백미도사", 0x74F
        );

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (Map.Entry<String, SubmissionParityReport.ActorParityComparison> entry : actors.entrySet()) {
            String actorName = entry.getKey();
            SubmissionParityReport.ActorParityComparison actor = entry.getValue();
            int primaryGuid = primaryAbilityByActor.get(actorName);
            int fallbackGuid = fallbackAbilityByActor.get(actorName);
            long localActorId = localActorIdByName.getOrDefault(actorName, 0L);

            Map<Integer, Long> localByTarget = capturedDamageEvents.stream()
                    .filter(e -> e.sourceId().value() == localActorId)
                    .filter(e -> e.actionId() == primaryGuid || e.actionId() == fallbackGuid)
                    .collect(Collectors.groupingBy(
                            e -> (int) e.targetId().value(),
                            Collectors.summingLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    ));

            List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    actor.fflogsActorId(),
                    primaryGuid
            );
            List<FflogsApiClient.DamageEventEntry> fallbackEvents = apiClient.fetchDamageDoneEventsByAbility(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    actor.fflogsActorId(),
                    fallbackGuid
            );

            Map<Integer, Long> fflogsByTarget = primaryEvents.stream()
                    .collect(Collectors.groupingBy(
                            FflogsApiClient.DamageEventEntry::targetId,
                            Collectors.summingLong(FflogsApiClient.DamageEventEntry::amount)
                    ));
            fallbackEvents.forEach(event -> fflogsByTarget.merge(event.targetId(), event.amount(), Long::sum));

            Map<Integer, Long> fflogsPrimaryByTarget = primaryEvents.stream()
                    .collect(Collectors.groupingBy(
                            FflogsApiClient.DamageEventEntry::targetId,
                            Collectors.summingLong(FflogsApiClient.DamageEventEntry::amount)
                    ));
            Map<Integer, Long> fflogsFallbackByTarget = fallbackEvents.stream()
                    .collect(Collectors.groupingBy(
                            FflogsApiClient.DamageEventEntry::targetId,
                            Collectors.summingLong(FflogsApiClient.DamageEventEntry::amount)
                    ));

            long localTotal = localByTarget.values().stream().mapToLong(Long::longValue).sum();
            long fflogsTotal = fflogsByTarget.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(
                    "heavy2.targetParity actor=%s primary=%s fallback=%s localTotal=%d fflogsTotal=%d%n",
                    actorName,
                    formatGuid(primaryGuid),
                    formatGuid(fallbackGuid),
                    localTotal,
                    fflogsTotal
            );
            System.out.printf(
                    "  fflogsPrimaryTotal=%d fflogsFallbackTotal=%d primaryEventCount=%d fallbackEventCount=%d%n",
                    fflogsPrimaryByTarget.values().stream().mapToLong(Long::longValue).sum(),
                    fflogsFallbackByTarget.values().stream().mapToLong(Long::longValue).sum(),
                    primaryEvents.size(),
                    fallbackEvents.size()
            );

            Set<Integer> targetIds = new HashSet<>();
            targetIds.addAll(localByTarget.keySet());
            targetIds.addAll(fflogsByTarget.keySet());
            List<Integer> sortedTargets = targetIds.stream().sorted().toList();
            for (Integer targetId : sortedTargets) {
                long local = localByTarget.getOrDefault(targetId, 0L);
                long fflogs = fflogsByTarget.getOrDefault(targetId, 0L);
                if (local == 0L && fflogs == 0L) {
                    continue;
                }
                String targetName = targetNameById.getOrDefault(targetId.longValue(), "?");
                System.out.printf(
                        "  target=%s(%s) local=%d fflogs=%d delta=%d%n",
                        Integer.toHexString(targetId).toUpperCase(),
                        targetName,
                        local,
                        fflogs,
                        local - fflogs
                );
            }
        }
    }

    @Test
    void debugHeavy2Fight1SamuraiTargetParity_printsHiganbanaTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                Map.of("재탄", 0x1D41),
                Map.of("재탄", 0x4CC),
                "heavy2.fight1"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTargetParity_printsHiganbanaTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Map.of("재탄", 0x1D41),
                Map.of("재탄", 0x4CC),
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2Fight1SamuraiDotAttributionModes_printsHiganbanaModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "?ы깂",
                0x1D41,
                "heavy2.fight1"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiDotAttributionModes_printsHiganbanaModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "?ы깂",
                0x1D41,
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2Fight1WhmSchTargetParity_printsDiaBiolysisTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                Map.of("백미도사", 0x4094, "젤리", 0x409C),
                Map.of("백미도사", 0x074F, "젤리", 0x0767),
                "heavy2.fight1.whm_sch"
        );
    }

    @Test
    void debugHeavy2Fight2WhmSchTargetParity_printsDiaBiolysisTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Map.of("백미도사", 0x4094, "젤리", 0x409C),
                Map.of("백미도사", 0x074F, "젤리", 0x0767),
                "heavy2.fight2.whm_sch"
        );
    }

    @Test
    void debugHeavy2Fight1WhmSchDotAttributionModes_printsDiaBiolysisModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "백미도사",
                0x4094,
                "heavy2.fight1.whm"
        );
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "젤리",
                0x409C,
                "heavy2.fight1.sch"
        );
    }

    @Test
    void debugHeavy2Fight2WhmSchDotAttributionModes_printsDiaBiolysisModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "백미도사",
                0x4094,
                "heavy2.fight2.whm"
        );
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "젤리",
                0x409C,
                "heavy2.fight2.sch"
        );
    }

    @Test
    void debugHeavy2Fight1ActorDeltas_printsSamWhmSchTotals() throws Exception {
        printActorParityComparisons(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                List.of("Samurai", "WhiteMage", "Scholar"),
                "heavy2.fight1.actorTotals"
        );
    }

    @Test
    void debugHeavy2Fight2ActorDeltas_printsSamWhmSchTotals() throws Exception {
        printActorParityComparisons(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                List.of("Samurai", "WhiteMage", "Scholar"),
                "heavy2.fight2.actorTotals"
        );
    }

    @Test
    void debugHeavy2Fight6SnapshotWeightVsAbilityTotals_printsSamSchWhmShares() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        Map<Integer, Integer> statusToGuid = Map.of(
                0x04CC, 0x1D41, // SAM
                0x0767, 0x409C, // SCH biolysis
                0x0F2B, 0x9094, // SCH baneful impaction
                0x074F, 0x4094  // WHM dia
        );
        Map<Integer, String> guidToActor = Map.of(
                0x1D41, "재탄",
                0x409C, "젤리",
                0x9094, "젤리",
                0x4094, "백미도사"
        );

        Map<Long, Map<String, Long>> rawDotByTargetActor = new HashMap<>();
        Map<Long, Map<Integer, Double>> snapshotWeightsByTargetGuid = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot) {
                if (dot.statusId() == 0 && Set.of("재탄", "젤리", "백미도사").contains(dot.sourceName())) {
                    rawDotByTargetActor
                            .computeIfAbsent(dot.targetId(), ignored -> new HashMap<>())
                            .merge(dot.sourceName(), dot.damage(), Long::sum);
                }
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
                for (com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw.StatusEntry status : snapshot.statuses()) {
                    Integer guid = statusToGuid.get(status.statusId());
                    if (guid == null) {
                        continue;
                    }
                    double weight = decodeSnapshotFloat(status.rawValueHex());
                    if (weight <= 0) {
                        continue;
                    }
                    snapshotWeightsByTargetGuid
                            .computeIfAbsent(snapshot.actorId(), ignored -> new HashMap<>())
                            .merge(guid, weight, Double::sum);
                }
            }
        }

        long primaryTarget = rawDotByTargetActor.entrySet().stream()
                .max((left, right) -> Long.compare(
                        left.getValue().values().stream().mapToLong(Long::longValue).sum(),
                        right.getValue().values().stream().mapToLong(Long::longValue).sum()
                ))
                .map(Map.Entry::getKey)
                .orElse(0L);
        assertTrue(primaryTarget != 0L);

        Map<String, Long> rawByActor = rawDotByTargetActor.getOrDefault(primaryTarget, Map.of());
        Map<Integer, Double> weightByGuid = snapshotWeightsByTargetGuid.getOrDefault(primaryTarget, Map.of());
        double totalTrackedWeight = weightByGuid.values().stream().mapToDouble(Double::doubleValue).sum();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        Map<String, Integer> actorPrimaryGuid = Map.of(
                "재탄", 0x1D41,
                "젤리", 0x409C,
                "백미도사", 0x4094
        );
        FflogsApiClient apiClient = buildConfiguredApiClient();

        System.out.printf(
                "heavy2.snapshotShare primaryTarget=%s rawDotByActor=%s totalTrackedWeight=%.4f%n",
                Long.toHexString(primaryTarget).toUpperCase(),
                rawByActor,
                totalTrackedWeight
        );

        for (Map.Entry<String, Integer> entry : actorPrimaryGuid.entrySet()) {
            String actorName = entry.getKey();
            int guid = entry.getValue();
            SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                    .filter(c -> actorName.equals(c.localName()))
                    .findFirst()
                    .orElseThrow();

            long localTotal = capturedDamageEvents.stream()
                    .filter(e -> actorName.equals(e.sourceName()) && e.actionId() == guid)
                    .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    .sum();
            long fflogsAbilityTotal = apiClient.fetchDamageDoneAbilities(
                            report.fflogs().reportCode(),
                            report.fflogs().selectedFightId(),
                            comparison.fflogsActorId()
                    ).stream()
                    .filter(a -> a.guid() != null && a.guid() == guid)
                    .mapToLong(a -> Math.round(a.total()))
                    .sum();
            double snapshotShare = totalTrackedWeight <= 0.0
                    ? 0.0
                    : weightByGuid.getOrDefault(guid, 0.0) / totalTrackedWeight;

            System.out.printf(
                    "  actor=%s guid=%s localTotal=%d fflogsAbilityTotal=%d delta=%d snapshotShare=%.4f rawSourceDot=%d%n",
                    actorName,
                    formatGuid(guid),
                    localTotal,
                    fflogsAbilityTotal,
                    localTotal - fflogsAbilityTotal,
                    snapshotShare,
                    rawByActor.getOrDefault(guidToActor.get(guid), 0L)
            );
        }
    }

    @Test
    void debugHeavy2Fight6GuidDirectVsDotInferred_printsSamSchWhmDecomposition() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        List<String> lines = Files.readAllLines(combatLog, StandardCharsets.UTF_8);
        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        Map<String, Integer> targetGuidByActor = Map.of(
                "재탄", 0x1D41,
                "젤리", 0x409C,
                "백미도사", 0x4094
        );

        Map<String, Long> rawDirectTotals = new HashMap<>();
        Map<String, Long> rawDirectHits = new HashMap<>();
        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (!(parsed instanceof NetworkAbilityRaw ability)) {
                continue;
            }
            for (Map.Entry<String, Integer> entry : targetGuidByActor.entrySet()) {
                if (!entry.getKey().equals(ability.actorName()) || ability.skillId() != entry.getValue()) {
                    continue;
                }
                String key = entry.getKey() + "#" + Integer.toHexString(entry.getValue()).toUpperCase();
                rawDirectTotals.merge(key, ability.damage(), Long::sum);
                rawDirectHits.merge(key, 1L, Long::sum);
            }
        }

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (Map.Entry<String, Integer> entry : targetGuidByActor.entrySet()) {
            String actorName = entry.getKey();
            int guid = entry.getValue();
            String key = actorName + "#" + Integer.toHexString(guid).toUpperCase();

            long emittedTotal = capturedDamageEvents.stream()
                    .filter(e -> actorName.equals(e.sourceName()) && e.actionId() == guid)
                    .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    .sum();
            long emittedHits = capturedDamageEvents.stream()
                    .filter(e -> actorName.equals(e.sourceName()) && e.actionId() == guid)
                    .count();
            long rawDirectTotal = rawDirectTotals.getOrDefault(key, 0L);
            long rawDirectHit = rawDirectHits.getOrDefault(key, 0L);
            long inferredDotTotal = emittedTotal - rawDirectTotal;
            long inferredDotHit = emittedHits - rawDirectHit;

            SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                    .filter(c -> actorName.equals(c.localName()))
                    .findFirst()
                    .orElseThrow();
            long fflogsAbilityTotal = apiClient.fetchDamageDoneAbilities(
                            report.fflogs().reportCode(),
                            report.fflogs().selectedFightId(),
                            comparison.fflogsActorId()
                    ).stream()
                    .filter(a -> a.guid() != null && a.guid() == guid)
                    .mapToLong(a -> Math.round(a.total()))
                    .sum();

            System.out.printf(
                    "heavy2.directVsDot actor=%s guid=%s emittedTotal=%d emittedHits=%d raw21Total=%d raw21Hits=%d inferredDotTotal=%d inferredDotHits=%d fflogsAbilityTotal=%d delta=%d%n",
                    actorName,
                    formatGuid(guid),
                    emittedTotal,
                    emittedHits,
                    rawDirectTotal,
                    rawDirectHit,
                    inferredDotTotal,
                    inferredDotHit,
                    fflogsAbilityTotal,
                    emittedTotal - fflogsAbilityTotal
            );
        }
    }

    @Test
    void debugHeavy2Fight6RawStatus0DotSourceBreakdown_printsTopSources() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        Map<Long, Integer> jobByActorId = new HashMap<>();
        Map<String, Long> totalBySource = new HashMap<>();
        Map<String, Long> countBySource = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded added) {
                if (added.jobId() != 0) {
                    jobByActorId.put(added.id(), added.jobId());
                }
                continue;
            }
            if (!(parsed instanceof DotTickRaw dot)) {
                continue;
            }
            if (!dot.isDot() || dot.statusId() != 0) {
                continue;
            }

            int sourceJobId = jobByActorId.getOrDefault(dot.sourceId(), 0);
            String sourceKey = dot.sourceName() + "(job=" + Integer.toHexString(sourceJobId).toUpperCase() + ")";
            totalBySource.merge(sourceKey, dot.damage(), Long::sum);
            countBySource.merge(sourceKey, 1L, Long::sum);
        }

        System.out.println("heavy2.rawStatus0DotSources top by totalDamage:");
        totalBySource.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  source=%s total=%d count=%d%n",
                        entry.getKey(),
                        entry.getValue(),
                        countBySource.getOrDefault(entry.getKey(), 0L)
                ));
    }

    private static String envOrProperty(String envKey, String... propertyKeys) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        for (String propertyKey : propertyKeys) {
            String propertyValue = System.getProperty(propertyKey);
            if (propertyValue != null && !propertyValue.isBlank()) {
                return propertyValue;
            }
            String yamlValue = APPLICATION_YAML.getProperty(propertyKey);
            if (yamlValue != null && !yamlValue.isBlank()) {
                return yamlValue;
            }
        }
        return "";
    }

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(Path.of("src", "main", "resources", "application.yml")));
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pacemeter.fflogs.client-id", "pace.fflogs.client-id");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pacemeter.fflogs.client-secret", "pace.fflogs.client-secret");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "FFLogs credentials are required for this diagnostic test");

        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);

        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pacemeter.fflogs.partition", "pace.fflogs.partition"));
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
        if (skillName.startsWith("Skill#")) {
            String hex = skillName.substring("Skill#".length());
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
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

    private static double decodeSnapshotFloat(String rawValueHex) {
        if (rawValueHex == null || rawValueHex.isBlank()) {
            return 0.0;
        }
        try {
            int bits = (int) Long.parseUnsignedLong(rawValueHex, 16);
            return Float.intBitsToFloat(bits);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static long extractSourceId(String type, String[] parts) {
        try {
            if (("21".equals(type) || "22".equals(type)) && parts.length > 2) {
                return Long.parseUnsignedLong(parts[2], 16);
            }
            if ("24".equals(type) && parts.length > 17) {
                return Long.parseUnsignedLong(parts[17], 16);
            }
            if (("26".equals(type) || "30".equals(type)) && parts.length > 5) {
                return Long.parseUnsignedLong(parts[5], 16);
            }
        } catch (Exception ignored) {
            return 0L;
        }
        return 0L;
    }

    private static String skillKey(String skillName, int skillId) {
        if (skillName == null || skillName.isBlank()) {
            return "Skill#" + Integer.toHexString(skillId).toUpperCase();
        }
        return skillName + " (" + Integer.toHexString(skillId).toUpperCase() + ")";
    }

    private void printActorAbilityTargetParity(
            String submissionId,
            int fightId,
            Map<String, Integer> primaryAbilityByActor,
            Map<String, Integer> fallbackAbilityByActor,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> capturedDamageEvents = new ArrayList<>();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
                        if (event instanceof com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent damageEvent) {
                            capturedDamageEvents.add(damageEvent);
                        }
                        return com.bohouse.pacemeter.core.engine.EngineResult.empty();
                    }

                    @Override
                    public void setCurrentPlayerId(ActorId playerId) {
                    }

                    @Override
                    public void setJobId(ActorId actorId, int jobId) {
                    }
                };
        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        Map<Long, String> targetNameById = new HashMap<>();
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        Map<String, SubmissionParityReport.ActorParityComparison> actors = report.comparisons().stream()
                .filter(comparison -> primaryAbilityByActor.containsKey(comparison.localName()))
                .collect(Collectors.toMap(
                        SubmissionParityReport.ActorParityComparison::localName,
                        comparison -> comparison,
                        (left, right) -> left
                ));
        assertEquals(primaryAbilityByActor.size(), actors.size());

        Map<String, Long> localActorIdByName = report.combat().actors().stream()
                .collect(Collectors.toMap(
                        CombatDebugSnapshot.ActorDebugEntry::name,
                        actor -> actor.actorId().value(),
                        (left, right) -> left
                ));

        FflogsApiClient apiClient = buildConfiguredApiClient();
        for (Map.Entry<String, SubmissionParityReport.ActorParityComparison> entry : actors.entrySet()) {
            String actorName = entry.getKey();
            SubmissionParityReport.ActorParityComparison actor = entry.getValue();
            int primaryGuid = primaryAbilityByActor.get(actorName);
            int fallbackGuid = fallbackAbilityByActor.getOrDefault(actorName, 0);
            long localActorId = localActorIdByName.getOrDefault(actorName, 0L);

            Map<Integer, Long> localByTarget = capturedDamageEvents.stream()
                    .filter(e -> e.sourceId().value() == localActorId)
                    .filter(e -> e.actionId() == primaryGuid || (fallbackGuid > 0 && e.actionId() == fallbackGuid))
                    .collect(Collectors.groupingBy(
                            e -> (int) e.targetId().value(),
                            Collectors.summingLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    ));

            List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    actor.fflogsActorId(),
                    primaryGuid
            );
            List<FflogsApiClient.DamageEventEntry> fallbackEvents = fallbackGuid > 0
                    ? apiClient.fetchDamageDoneEventsByAbility(
                            report.fflogs().reportCode(),
                            report.fflogs().selectedFightId(),
                            actor.fflogsActorId(),
                            fallbackGuid
                    )
                    : List.of();

            Map<Integer, Long> fflogsByTarget = primaryEvents.stream()
                    .collect(Collectors.groupingBy(
                            FflogsApiClient.DamageEventEntry::targetId,
                            Collectors.summingLong(FflogsApiClient.DamageEventEntry::amount)
                    ));
            fallbackEvents.forEach(event -> fflogsByTarget.merge(event.targetId(), event.amount(), Long::sum));

            long localTotal = localByTarget.values().stream().mapToLong(Long::longValue).sum();
            long fflogsTotal = fflogsByTarget.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(
                    "%s targetParity fight=%d actor=%s primary=%s fallback=%s localTotal=%d fflogsTotal=%d delta=%d%n",
                    label,
                    fightId,
                    actorName,
                    formatGuid(primaryGuid),
                    formatGuid(fallbackGuid),
                    localTotal,
                    fflogsTotal,
                    localTotal - fflogsTotal
            );

            Set<Integer> targetIds = new HashSet<>();
            targetIds.addAll(localByTarget.keySet());
            targetIds.addAll(fflogsByTarget.keySet());
            targetIds.stream()
                    .sorted((left, right) -> Long.compare(
                            Math.abs(localByTarget.getOrDefault(right, 0L) - fflogsByTarget.getOrDefault(right, 0L)),
                            Math.abs(localByTarget.getOrDefault(left, 0L) - fflogsByTarget.getOrDefault(left, 0L))
                    ))
                    .limit(12)
                    .forEach(targetId -> {
                        long local = localByTarget.getOrDefault(targetId, 0L);
                        long fflogs = fflogsByTarget.getOrDefault(targetId, 0L);
                        String targetName = targetNameById.getOrDefault(targetId.longValue(), "?");
                        System.out.printf(
                                "  target=%s(%s) local=%d fflogs=%d delta=%d%n",
                                Integer.toHexString(targetId).toUpperCase(),
                                targetName,
                                local,
                                fflogs,
                                local - fflogs
                        );
                    });
        }
    }

    private void printDotAttributionBreakdown(
            String submissionId,
            int fightId,
            String actorName,
            int actionId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        CombatService combatService = new CombatService(
                new CombatEngine(),
                snapshot -> {},
                (name, zone) -> Optional.empty(),
                territoryId -> Optional.empty()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ActIngestionService ingestion = new ActIngestionService(
                combatService,
                combatService,
                new FflogsZoneLookup(objectMapper)
        );

        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        String resolvedActorName = report.comparisons().stream()
                .filter(comparison -> actorName.equals(comparison.localName()))
                .map(SubmissionParityReport.ActorParityComparison::localName)
                .findFirst()
                .orElseGet(() -> report.comparisons().stream()
                        .filter(comparison -> actorName.equals(comparison.fflogsType()))
                        .map(SubmissionParityReport.ActorParityComparison::localName)
                        .findFirst()
                        .orElseThrow());

        long actorId = report.combat().actors().stream()
                .filter(actor -> resolvedActorName.equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        String actionHex = Integer.toHexString(actionId).toUpperCase();
        String sourceHex = Long.toHexString(actorId).toUpperCase();
        Map<String, Long> assignedAmounts = ingestion.debugDotAttributionAssignedAmounts();
        Map<String, Long> assignedHits = ingestion.debugDotAttributionAssignedHitCounts();

        System.out.printf(
                "%s dotModeBreakdown fight=%d actor=%s action=%s%n",
                label,
                fightId,
                resolvedActorName,
                actionHex
        );
        assignedAmounts.entrySet().stream()
                .filter(entry -> entry.getKey().contains("source=" + sourceHex))
                .filter(entry -> entry.getKey().contains("action=" + actionHex))
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> System.out.printf(
                        "  mode=%s amount=%d hits=%d%n",
                        entry.getKey(),
                        entry.getValue(),
                        assignedHits.getOrDefault(entry.getKey(), 0L)
                ));
    }

    private void printActorParityComparisons(
            String submissionId,
            int fightId,
            List<String> actorNames,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        report.comparisons().stream()
                .filter(comparison -> actorNames.contains(comparison.localName())
                        || actorNames.contains(comparison.fflogsType()))
                .sorted(Comparator.comparingDouble(
                        SubmissionParityReport.ActorParityComparison::rdpsDeltaRatio
                ).reversed())
                .forEach(comparison -> System.out.printf(
                        "%s fight=%d actor=%s job=%s local=%.1f fflogs=%.1f delta=%.1f ratio=%.4f%n",
                        label,
                        fightId,
                        comparison.localName(),
                        comparison.fflogsType(),
                        comparison.localOnlineRdps(),
                        comparison.fflogsRdpsPerSecond(),
                        comparison.rdpsDelta(),
                        comparison.rdpsDeltaRatio()
                ));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean isRecent(Instant ts, RecentDotEvidence evidence, Duration window) {
        if (evidence == null) {
            return false;
        }
        long deltaMs = Math.abs(Duration.between(evidence.ts(), ts).toMillis());
        return deltaMs <= window.toMillis();
    }

    private static Integer resolveExpectedActionFromExactEvidence(
            Instant tickTs,
            DotEvidence actionEvidence,
            DotEvidence statusEvidence,
            Map<Integer, Integer> statusToAction
    ) {
        Instant cutoff = tickTs.minusMillis(90_000L);
        Integer actionCandidate = null;
        if (actionEvidence != null && !actionEvidence.ts().isBefore(cutoff)) {
            actionCandidate = actionEvidence.id();
        }
        Integer statusMapped = null;
        if (statusEvidence != null && !statusEvidence.ts().isBefore(cutoff)) {
            statusMapped = statusToAction.get(statusEvidence.id());
            if (statusMapped == null) {
                statusMapped = statusEvidence.id();
            }
        }
        if (actionCandidate != null && statusMapped != null) {
            return actionCandidate.equals(statusMapped) ? actionCandidate : null;
        }
        return statusMapped != null ? statusMapped : actionCandidate;
    }

    private static Map<SourceActionKey, Long> aggregateAssignedStatus0BySourceAction(Map<String, Long> assignedAmounts) {
        Map<SourceActionKey, Long> result = new HashMap<>();
        for (Map.Entry<String, Long> entry : assignedAmounts.entrySet()) {
            String key = entry.getKey();
            String[] parts = key.split("\\|");
            if (parts.length < 3 || !parts[0].startsWith("status0_")) {
                continue;
            }
            String sourceHex = parts[1].replace("source=", "");
            String actionHex = parts[2].replace("action=", "");
            try {
                long sourceId = Long.parseUnsignedLong(sourceHex, 16);
                int actionId = Integer.parseInt(actionHex, 16);
                result.merge(new SourceActionKey(sourceId, actionId), entry.getValue(), Long::sum);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase();
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

    private record SourceTargetKey(long sourceId, long targetId) {
    }

    private record RecentDotEvidence(
            Instant ts,
            long targetId,
            int statusId,
            String targetName
    ) {
    }

    private record DotEvidence(int id, Instant ts) {
    }

    private record SourceActionKey(long sourceId, int actionId) {
    }

    private record SourceActionDelta(long sourceId, int actionId, long expected, long assigned, long delta) {
    }

    private record PetContributionSummary(
            int petCount,
            long totalDamage,
            double granted,
            double received
    ) {
    }

}
