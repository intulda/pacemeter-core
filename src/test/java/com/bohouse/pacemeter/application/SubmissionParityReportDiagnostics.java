package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.BuffApplyRaw;
import com.bohouse.pacemeter.adapter.inbound.actws.BuffRemoveRaw;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    void debugHeavy2Fight6DragoonGuidSkillDelta_printsActionLevelMismatch() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        List<SubmissionParityReport.ActorParityComparison> targets = report.comparisons().stream()
                .filter(c -> "Dragoon".equals(c.fflogsType()) || "구려".equals(c.localName()))
                .sorted((a, b) -> Double.compare(Math.abs(b.rdpsDeltaRatio()), Math.abs(a.rdpsDeltaRatio())))
                .limit(1)
                .toList();
        assertEquals(1, targets.size());

        FflogsApiClient apiClient = buildConfiguredApiClient();
        Map<String, CombatDebugSnapshot.ActorDebugEntry> combatByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry actor : report.combat().actors()) {
            combatByName.put(actor.name(), actor);
        }
        Map<ActorId, List<CombatDebugSnapshot.SkillDebugEntry>> skillsByActorId = new HashMap<>();
        for (CombatDebugSnapshot.ActorSkillBreakdown breakdown : report.combat().skillBreakdowns()) {
            skillsByActorId.put(breakdown.actorId(), breakdown.skills());
        }

        SubmissionParityReport.ActorParityComparison actor = targets.get(0);
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
                "heavy2.fight6.drg.guidDelta actor=%s job=%s rdpsDelta=%.1f ratio=%.3f%n",
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

        record GuidDelta(Integer guid, long localTotal, long fflogsTotal, long delta) {}
        List<GuidDelta> deltas = localByGuid.entrySet().stream()
                .map(entry -> {
                    Integer localSkillId = entry.getKey();
                    long localTotal = entry.getValue();
                    FflogsApiClient.AbilityDamageEntry matched = fflogsByGuid.get(localSkillId);
                    long fflogsTotal = matched == null ? 0L : Math.round(matched.total());
                    return new GuidDelta(localSkillId, localTotal, fflogsTotal, localTotal - fflogsTotal);
                })
                .sorted((a, b) -> Long.compare(Math.abs(b.delta()), Math.abs(a.delta())))
                .limit(20)
                .toList();
        deltas.forEach(entry -> System.out.printf(
                "  guid=%s local=%d fflogs=%d delta=%d%n",
                formatGuid(entry.guid()),
                entry.localTotal(),
                entry.fflogsTotal(),
                entry.delta()
        ));

        Set<Integer> localIds = localByGuid.keySet();
        List<String> missingHigh = abilities.stream()
                .filter(a -> a.guid() != null && !localIds.contains(a.guid()))
                .sorted((a, b) -> Double.compare(b.total(), a.total()))
                .limit(10)
                .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                .toList();
        System.out.println("  fflogsMissingInLocalTop=" + missingHigh);
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
    void debugHeavy2Fight2SamuraiDotAttributionModesByTarget_printsHiganbanaTargetModeBreakdown() throws Exception {
        printDotAttributionBreakdownByTarget(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFallbackTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printSplitModeEvidenceBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "status0_fallback_tracked_target_split",
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonFallbackTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printSplitModeEvidenceBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "status0_fallback_tracked_target_split",
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonFallbackTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printSplitModeEvidenceBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "status0_fallback_tracked_target_split",
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFallbackTrackedTargetSplitSourceLifecycle_printsLeakConditions() throws Exception {
        printFallbackSplitSourceLifecycleBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonFallbackTrackedTargetSplitSourceLifecycle_printsLeakConditions() throws Exception {
        printFallbackSplitSourceLifecycleBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonFallbackTrackedTargetSplitSourceLifecycle_printsLeakConditions() throws Exception {
        printFallbackSplitSourceLifecycleBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFallbackTrackedTargetSplitForeignSourceWindow_printsRawEvidence() throws Exception {
        printFallbackSplitForeignSourceWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x10128857L,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTrackedTargetSplitForeignSourceWindow_printsRawEvidence() throws Exception {
        printSplitForeignSourceWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x10128857L,
                "status0_tracked_target_split",
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTrackedTargetSplitLocalSourceWindow_printsRawEvidence() throws Exception {
        printSplitForeignSourceWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x102B1904L,
                "status0_tracked_target_split",
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiPrimaryFallbackTotals_prints1d41And04ccTotals() throws Exception {
        printActorGuidTotals(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                List.of(0x1D41, 0x04CC),
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiAlignedEventDiff_prints1d41LocalVsFflogsSequences() throws Exception {
        printActorAlignedEventDiff(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x04CC,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFflogsAbilities_printsNearbyAbilityRows() throws Exception {
        printActorFflogsAbilities(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                List.of("Higan", "彼岸", "1D41"),
                Set.of(0x1D41, 0x04CC),
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFflogsAbilityVsEvents_printsHiganbanaSurfaceDelta() throws Exception {
        printActorFflogsAbilityVsEvents(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x04CC,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiFflogsActorTotalVsEvents_printsSurfaceDelta() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2
        );
        assertEquals("ok", report.fflogs().status());
        assertEquals(2, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> "Samurai".equals(c.fflogsType()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        long eventTotal = apiClient.fetchDamageDoneEvents(
                        report.fflogs().reportCode(),
                        report.fflogs().selectedFightId(),
                        comparison.fflogsActorId()
                ).stream()
                .mapToLong(FflogsApiClient.DamageEventEntry::amount)
                .sum();
        long tableTotal = Math.round(comparison.fflogsTotal());

        System.out.printf(
                "heavy2.fight2.sam actorTotalVsEvents actor=%s tableTotal=%d eventTotal=%d delta=%d localTotal=%d%n",
                comparison.localName(),
                tableTotal,
                eventTotal,
                eventTotal - tableTotal,
                comparison.localTotalDamage()
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiDirectVsDot_prints1d41Decomposition() throws Exception {
        printActorGuidDirectVsDot(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiStatus0TargetSourceBreakdown_printsTopTargetRawSourceMix() throws Exception {
        printStatus0TargetSourceBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2AprilReportSummary_printsFightListForKa37() throws Exception {
        FflogsApiClient apiClient = buildConfiguredApiClient();
        Optional<FflogsApiClient.ReportSummary> summaryOpt = apiClient.fetchReportSummary("KA37dCnZcmHWRfF8");
        assertTrue(summaryOpt.isPresent());
        FflogsApiClient.ReportSummary summary = summaryOpt.orElseThrow();

        System.out.printf(
                "heavy2.april.summary reportCode=%s start=%d fights=%d%n",
                summary.reportCode(),
                summary.startTime(),
                summary.fights().size()
        );
        summary.fights().forEach(fight -> System.out.printf(
                "  fight=%d encounterId=%d name=%s start=%d end=%d kill=%s%n",
                fight.id(),
                fight.encounterId(),
                fight.name(),
                fight.startTime(),
                fight.endTime(),
                fight.kill()
        ));
    }

    @Test
    void debugHeavy2AprilFight3Parity_printsTopActorDeltas() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorParityComparisons(
                    submissionId,
                    3,
                    List.of("Samurai", "Dragoon", "WhiteMage", "Scholar", "Pictomancer"),
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3Roster_printsAllMatchedActors() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            SubmissionParityReportService service = buildConfiguredHeavy4Service();
            SubmissionParityReport report = service.buildReportForFight(submissionId, 3);
            assertEquals("ok", report.fflogs().status());
            assertEquals(3, report.fflogs().selectedFightId());

            System.out.printf(
                    "heavy2.april.fight3.roster matched=%d unmatchedLocal=%d unmatchedFflogs=%d%n",
                    report.comparisons().size(),
                    report.unmatchedLocalActors().size(),
                    report.unmatchedFflogsActors().size()
            );
            report.comparisons().forEach(comparison -> System.out.printf(
                    "  localName=%s fflogsType=%s fflogsName=%s local=%.1f fflogs=%.1f delta=%.1f ratio=%.4f%n",
                    comparison.localName(),
                    comparison.fflogsType(),
                    comparison.fflogsName(),
                    comparison.localOnlineRdps(),
                    comparison.fflogsRdpsPerSecond(),
                    comparison.rdpsDelta(),
                    comparison.rdpsDeltaRatio()
            ));
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonDirectVsDot_prints64acDecomposition() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorGuidDirectVsDot(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonStatus0TargetSourceBreakdown_prints64acTargetMix() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printStatus0TargetSourceBreakdown(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonTargetParity_printsChaoticSpringTargetDeltas() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorAbilityTargetParity(
                    submissionId,
                    3,
                    Map.of("치삐", 0x64AC),
                    Map.of("치삐", 0x0A9F),
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonActiveSubsetLeak_printsSourceMatchedTargets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorActiveSubsetLeak(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight1SamuraiActiveSubsetLeak_printsSourceMatchedTargets() throws Exception {
        printActorActiveSubsetLeak(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "Samurai",
                0x1D41,
                "heavy2.fight1.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiActiveSubsetLeak_printsSourceMatchedTargets() throws Exception {
        printActorActiveSubsetLeak(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight1DragoonActiveSubsetLeak_printsSourceMatchedTargets() throws Exception {
        printActorActiveSubsetLeak(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "Dragoon",
                0x64AC,
                "heavy2.fight1.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonActiveSubsetLeak_printsSourceMatchedTargets() throws Exception {
        printActorActiveSubsetLeak(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight1SamuraiAcceptedBySourcePotential_printsResolvedActions() throws Exception {
        printActorAcceptedBySourcePotential(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "Samurai",
                0x1D41,
                "heavy2.fight1.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidence(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidence(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidence(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonDeepBlueTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidenceForTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                Set.of(0x4000154DL),
                "heavy2.fight2.drg.deepblue"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonPrisonTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidenceForTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                Set.of(0x40001729L),
                "heavy2.fight2.drg.prison"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonTrackedTargetSplitAssignedByActiveTargets_prints64acMix() throws Exception {
        printActorTrackedTargetSplitAssignedByActiveTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2ProblemTargetsSnapshotSelection_printsHeavy2TargetPaths() throws Exception {
        printProblemTargetSnapshotSelection(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Set.of(0x4000154CL, 0x4000154DL, 0x40001729L),
                "heavy2.fight2.problemTargets"
        );
    }

    @Test
    void debugHeavy2Fight2ProblemTargetsSameSourceButSplit_printsHeavy2TargetLeaks() throws Exception {
        printProblemTargetSameSourceButTrackedSplit(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Set.of(0x4000154CL, 0x4000154DL, 0x40001729L),
                "heavy2.fight2.problemTargets"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonSameSourceTrackedSplitMix_prints64acLeakMix() throws Exception {
        printActorSameSourceTrackedSplitMix(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonSameSourceTrackedSplitMix_prints64acLeakMix() throws Exception {
        printActorSameSourceTrackedSplitMix(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonSameSourceTrackedSplitMix_prints64acLeakMix() throws Exception {
        printActorSameSourceTrackedSplitMix(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonForeignTrackedSplitContributors_prints64acLeakSources() throws Exception {
        printActorForeignTrackedSplitContributors(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonForeignTrackedSplitContributors_prints64acLeakSources() throws Exception {
        printActorForeignTrackedSplitContributors(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonForeignTrackedSplitContributors_prints64acLeakSources() throws Exception {
        printActorForeignTrackedSplitContributors(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonForeignSplitByActiveTargets_prints64acLeakBudget() throws Exception {
        printActorForeignSplitBudgetByActiveTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonTrackedTargetSplitSameActionMultiTargetWindow_prints64acContext() throws Exception {
        printTrackedTargetSplitSameActionMultiTargetWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonTrackedTargetSplitSameActionDualTargetLifecycle_prints64acLifecycle() throws Exception {
        printTrackedTargetSplitSameActionDualTargetLifecycle(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonAlignedEventWindow_prints64acSampleWindow() throws Exception {
        printActorAlignedEventWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                75_000L,
                7_000L,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonTrackedTargetSplitTargetSeenAge_prints64acBuckets() throws Exception {
        printSplitModeTargetSeenAgeBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "status0_tracked_target_split",
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonTrackedTargetSplitAssignedByActiveTargets_prints64acMix() throws Exception {
        printActorTrackedTargetSplitAssignedByActiveTargets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonTrackedTargetSplitAssignedByActiveTargets_prints64acMix() throws Exception {
        printActorTrackedTargetSplitAssignedByActiveTargets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonTrackedTargetSplitEvidence_printsLeakConditions() throws Exception {
        printActorTrackedTargetSplitEvidence(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitContaminationAcrossSelectedFights_printsEvidenceBuckets() throws Exception {
        printTrackedTargetSplitContaminationBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitContaminationBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
        printTrackedTargetSplitContaminationBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitContaminationBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitCandidateCoverageAcrossSelectedFights_printsShare() throws Exception {
        printTrackedTargetSplitCandidateCoverage(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitCandidateCoverage(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
        printTrackedTargetSplitCandidateCoverage(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitCandidateCoverage(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitCoverageMatrixAcrossSelectedFights_printsCrossValidationMatrix() throws Exception {
        printTrackedTargetSplitCoverageMatrix(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitCoverageMatrix(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
        printTrackedTargetSplitCoverageMatrix(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitCoverageMatrix(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitEvidenceAgeAcrossSelectedFights_printsStaleTargetMismatchBuckets() throws Exception {
        printTrackedTargetSplitEvidenceAgeBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                1,
                "Samurai",
                0x1D41,
                "heavy2.fight1.sam"
        );
        printTrackedTargetSplitEvidenceAgeBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitEvidenceAgeBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
        printTrackedTargetSplitEvidenceAgeBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitEvidenceAgeBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitExactComponentAcrossSelectedFights_printsExactEvidenceBuckets() throws Exception {
        printTrackedTargetSplitExactComponentBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitExactComponentBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitExactComponentBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugTrackedTargetSplitSameActionTargetsAcrossSelectedFights_printsGlobalSameActionBuckets() throws Exception {
        printTrackedTargetSplitSameActionTargetBuckets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
        printTrackedTargetSplitSameActionTargetBuckets(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
        printTrackedTargetSplitSameActionTargetBuckets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTrackedTargetSplitSameActionMultiTargetWindow_printsRawEvidence() throws Exception {
        printTrackedTargetSplitSameActionMultiTargetWindow(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiTrackedTargetSplitSameActionDualTargetLifecycle_printsApplyRemoveTimeline() throws Exception {
        printTrackedTargetSplitSameActionDualTargetLifecycle(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                0x04CC,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2AprilFight3DragoonAcceptedBySourcePotential_printsResolvedActions() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorAcceptedBySourcePotential(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonRawLifecycle_printsChaoticSpringOnMisattributedTargets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorDotLifecycle(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    Set.of(0x40006DF3L, 0x40006DF4L, 0x40006FA9L),
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3FflogsActors_printsActorIds() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printFflogsActors(submissionId, 3, "heavy2.april.fight3");
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonTargetTimeline_printsLocalVsFflogsTargets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorTargetTimeline(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonWindowedLocalTotals_prints64acDamageInsideFflogsWindows() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorWindowedLocalTotals(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonHitLeak_prints64acLocalHitsOutsideFflogsWindows() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorHitLeakAgainstFflogsWindows(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonBoundaryWindow_prints64acEventsAroundFflogsStart() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorDotLifecycleWindow(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    Set.of(0x40006DF3L, 0x40006DF4L, 0x40006FA9L),
                    280_000L,
                    305_000L,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3BoundarySnapshots_printsTargetSnapshotsAroundFflogsStart() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printSnapshotWindow(
                    submissionId,
                    3,
                    Set.of(0x40006DF3L, 0x40006DF4L),
                    280_000L,
                    305_000L,
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonFflogsFirst64acEvents_printsEarliestBuckets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorFirstFflogsEventsByAbility(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3FflogsFightOffset_printsSelectedFightStartOffset() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printSelectedFightOffset(submissionId, 3, "heavy2.april.fight3");
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonAlignedEventDiff_prints64acLocalVsFflogsSequences() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorAlignedEventDiff(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonFflogsAbilityBuckets_printsChaoticSpringAliases() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorFflogsAbilityBuckets(
                    submissionId,
                    3,
                    "Dragoon",
                    Set.of(0x64AC, 0x0A9F),
                    List.of("spring", "chaotic"),
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2AprilFight3DragoonFflogsAbilityVsEvents_printsChaoticSpringSurfaceDelta() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printActorFflogsAbilityVsEvents(
                    submissionId,
                    3,
                    "Dragoon",
                    0x64AC,
                    0x0A9F,
                    "heavy2.april.fight3.drg"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight2SnapshotTiming_printsProblemTargetSnapshotOrder() throws Exception {
        printStatus0SnapshotTiming(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Set.of(0x4000154CL, 0x4000154DL, 0x40001729L),
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3SnapshotTiming_printsProblemTargetSnapshotOrder() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printStatus0SnapshotTiming(
                    submissionId,
                    3,
                    Set.of(0x40006DF3L, 0x40006DF4L, 0x40006FA9L),
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight2SnapshotWeights_printsProblemTargetLatestSnapshotMix() throws Exception {
        printProblemTargetSnapshotWeights(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Set.of(0x4000154CL, 0x4000154DL, 0x40001729L),
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3SnapshotWeights_printsProblemTargetLatestSnapshotMix() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printProblemTargetSnapshotWeights(
                    submissionId,
                    3,
                    Set.of(0x40006DF3L, 0x40006DF4L, 0x40006FA9L),
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight2SnapshotSelection_printsStatus0SelectionPathByRawSource() throws Exception {
        printProblemTargetSnapshotSelection(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Set.of(0x4000154CL, 0x4000154DL, 0x40001729L),
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3SnapshotSelection_printsStatus0SelectionPathByRawSource() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printProblemTargetSnapshotSelection(
                    submissionId,
                    3,
                    Set.of(0x40006DF3L, 0x40006DF4L, 0x40006FA9L),
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight2SnapshotSelectionBySourceClass_printsPathMix() throws Exception {
        printSnapshotSelectionBySourceClass(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3SnapshotSelectionBySourceClass_printsPathMix() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printSnapshotSelectionBySourceClass(
                    submissionId,
                    3,
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugLindwurmFight8SnapshotSelectionBySourceClass_printsPathMix() throws Exception {
        printSnapshotSelectionBySourceClass(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "lindwurm.fight8"
        );
    }

    @Test
    void debugHeavy2Fight2UnknownSnapshotSelectionTargets_printsActiveSubsetTargets() throws Exception {
        printUnknownSnapshotSelectionTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3UnknownSnapshotSelectionTargets_printsActiveSubsetTargets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printUnknownSnapshotSelectionTargets(
                    submissionId,
                    3,
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugLindwurmFight8UnknownSnapshotSelectionTargets_printsActiveSubsetTargets() throws Exception {
        printUnknownSnapshotSelectionTargets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "lindwurm.fight8"
        );
    }

    @Test
    void debugHeavy2Fight2PartyActiveSubsetTargets_printsTopTargets() throws Exception {
        printPartyActiveSubsetTargets(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "heavy2.fight2"
        );
    }

    @Test
    void debugLindwurmFight8PartyActiveSubsetTargets_printsTopTargets() throws Exception {
        printPartyActiveSubsetTargets(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "lindwurm.fight8"
        );
    }

    @Test
    void debugHeavy2AprilFight3PartyActiveSubsetTargets_printsTopTargets() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printPartyActiveSubsetTargets(
                    submissionId,
                    3,
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugHeavy2Fight2UnknownActiveSubsetStructure_printsConcurrentTargetCounts() throws Exception {
        printUnknownActiveSubsetStructure(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "heavy2.fight2"
        );
    }

    @Test
    void debugHeavy2AprilFight3UnknownActiveSubsetStructure_printsConcurrentTargetCounts() throws Exception {
        String submissionId = createHeavy2AprilDiagnosticSubmission();
        try {
            printUnknownActiveSubsetStructure(
                    submissionId,
                    3,
                    "heavy2.april.fight3"
            );
        } finally {
            deleteDiagnosticSubmission(submissionId);
        }
    }

    @Test
    void debugLindwurmFight8UnknownActiveSubsetStructure_printsConcurrentTargetCounts() throws Exception {
        printUnknownActiveSubsetStructure(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "lindwurm.fight8"
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
    void debugHeavy2Fight2DragoonTargetParity_printsChaoticSpringTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                Map.of("구려", 0x64AC),
                Map.of("구려", 0x0A9F),
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonDotAttributionModes_printsChaoticSpringModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "구려",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonWeightedSplitTargets_prints64acWeightedRecipients() throws Exception {
        printModeBreakdownForSource(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "구려",
                "status0_weighted_tracked_target_split",
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiWeightedEntryEvidence_prints1d41Conditions() throws Exception {
        printWeightedTrackedTargetSplitEntryEvidence(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiWeightedSourceContributors_prints1d41RawContributors() throws Exception {
        printWeightedTrackedTargetSplitRawContributors(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Samurai",
                0x1D41,
                "heavy2.fight2.sam"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonWeightedActionRecipients_prints64acForeignActionMix() throws Exception {
        printWeightedTrackedTargetSplitActionRecipients(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonWeightedActionRecipients_prints64acForeignActionMix() throws Exception {
        printWeightedTrackedTargetSplitActionRecipients(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugLindwurmFight8DragoonWeightedActionRecipients_prints64acForeignActionMix() throws Exception {
        printWeightedTrackedTargetSplitActionRecipients(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                "Dragoon",
                0x64AC,
                "lindwurm.fight8.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonDirectVsDot_prints64acDecomposition() throws Exception {
        printActorGuidDirectVsDot(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonWindowedLocalTotals_prints64acDamageInsideFflogsWindows() throws Exception {
        printActorWindowedLocalTotals(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonHitLeak_prints64acLocalHitsOutsideFflogsWindows() throws Exception {
        printActorHitLeakAgainstFflogsWindows(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonFflogsAbilityVsEvents_printsChaoticSpringSurfaceDelta() throws Exception {
        printActorFflogsAbilityVsEvents(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonFflogsAbilityBuckets_printsChaoticSpringBuckets() throws Exception {
        printActorFflogsAbilities(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                List.of("chaotic spring"),
                Set.of(0x64AC, 0x0A9F),
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonStatus0TargetSourceBreakdown_prints64acTargetMix() throws Exception {
        printStatus0TargetSourceBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonGuidSurfaceByTarget_prints64acDirectAndDotMix() throws Exception {
        printActorGuidSurfaceByTarget(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy2Fight2DragoonAlignedEventDiff_prints64acLocalVsFflogsSequences() throws Exception {
        printActorAlignedEventDiff(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                "Dragoon",
                0x64AC,
                0x0A9F,
                "heavy2.fight2.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonTargetParity_printsChaoticSpringTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                Map.of("치삐", 0x64AC),
                Map.of("치삐", 0x0A9F),
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonFflogsAbilityBuckets_printsChaoticSpringBuckets() throws Exception {
        printActorFflogsAbilities(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "Dragoon",
                List.of("chaotic spring"),
                Set.of(0x64AC, 0x0A9F),
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugHeavy4Fight5DragoonDotAttributionModes_printsChaoticSpringModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                "치삐",
                0x64AC,
                "heavy4.fight5.drg"
        );
    }

    @Test
    void debugHeavy2Fight6DragoonTargetParity_printsChaoticSpringTargetDeltas() throws Exception {
        printActorAbilityTargetParity(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                6,
                Map.of("구려", 0x64AC),
                Map.of("구려", 0x0A9F),
                "heavy2.fight6.drg"
        );
    }

    @Test
    void debugHeavy2Fight6DragoonDotAttributionModes_printsChaoticSpringModeBreakdown() throws Exception {
        printDotAttributionBreakdown(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                6,
                "구려",
                0x64AC,
                "heavy2.fight6.drg"
        );
    }

    @Test
    void debugHeavy2Fight6DragoonActorDelta_printsTotalsAndTopSkills() throws Exception {
        printActorParityComparisons(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                6,
                List.of("Dragoon"),
                "heavy2.fight6.drg.actorTotals"
        );
    }

    @Test
    void debugHeavy2Fight6DragoonGuidSkillDeltaByJob_printsActionLevelMismatch() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", 6);
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison actor = report.comparisons().stream()
                .filter(c -> "Dragoon".equals(c.fflogsType()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        Map<String, CombatDebugSnapshot.ActorDebugEntry> combatByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry combatActor : report.combat().actors()) {
            combatByName.put(combatActor.name(), combatActor);
        }
        Map<ActorId, List<CombatDebugSnapshot.SkillDebugEntry>> skillsByActorId = new HashMap<>();
        for (CombatDebugSnapshot.ActorSkillBreakdown breakdown : report.combat().skillBreakdowns()) {
            skillsByActorId.put(breakdown.actorId(), breakdown.skills());
        }

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
                "heavy2.fight6.drg.guidDeltaByJob actor=%s job=%s rdpsDelta=%.1f ratio=%.3f%n",
                actor.localName(),
                actor.fflogsType(),
                actor.rdpsDelta(),
                actor.rdpsDeltaRatio()
        );

        record GuidDelta(Integer guid, long localTotal, long fflogsTotal, long delta) {}
        List<GuidDelta> deltas = new ArrayList<>();
        for (CombatDebugSnapshot.SkillDebugEntry localSkill : fullLocalSkills) {
            Integer localSkillId = extractLocalSkillId(localSkill.skillName());
            if (localSkillId == null || localSkillId <= 0) {
                continue;
            }
            long localTotal = localSkill.totalDamage();
            FflogsApiClient.AbilityDamageEntry matched = fflogsByGuid.get(localSkillId);
            long fflogsTotal = matched == null ? 0L : Math.round(matched.total());
            deltas.add(new GuidDelta(localSkillId, localTotal, fflogsTotal, localTotal - fflogsTotal));
        }
        deltas.stream()
                .sorted((a, b) -> Long.compare(Math.abs(b.delta()), Math.abs(a.delta())))
                .limit(20)
                .forEach(entry -> System.out.printf(
                        "  guid=%s local=%d fflogs=%d delta=%d%n",
                        formatGuid(entry.guid()),
                        entry.localTotal(),
                        entry.fflogsTotal(),
                        entry.delta()
                ));

        Set<Integer> localIds = deltas.stream()
                .map(GuidDelta::guid)
                .collect(Collectors.toSet());
        List<String> missingHigh = abilities.stream()
                .filter(a -> a.guid() != null && !localIds.contains(a.guid()))
                .sorted((a, b) -> Double.compare(b.total(), a.total()))
                .limit(10)
                .map(a -> a.name() + "(" + formatGuid(a.guid()) + "):" + Math.round(a.total()))
                .toList();
        System.out.println("  fflogsMissingInLocalTop=" + missingHigh);
    }

    @Test
    void debugHeavy2Fight6DragoonLocal64acBreakdown_printsSplitEntries() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", 6);
        assertEquals("ok", report.fflogs().status());
        assertEquals(6, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison actor = report.comparisons().stream()
                .filter(c -> "Dragoon".equals(c.fflogsType()))
                .findFirst()
                .orElseThrow();

        Map<String, CombatDebugSnapshot.ActorDebugEntry> combatByName = new HashMap<>();
        for (CombatDebugSnapshot.ActorDebugEntry combatActor : report.combat().actors()) {
            combatByName.put(combatActor.name(), combatActor);
        }
        Map<ActorId, List<CombatDebugSnapshot.SkillDebugEntry>> skillsByActorId = new HashMap<>();
        for (CombatDebugSnapshot.ActorSkillBreakdown breakdown : report.combat().skillBreakdowns()) {
            skillsByActorId.put(breakdown.actorId(), breakdown.skills());
        }

        CombatDebugSnapshot.ActorDebugEntry combatActor = combatByName.get(actor.localName());
        List<CombatDebugSnapshot.SkillDebugEntry> fullLocalSkills = combatActor == null
                ? List.of()
                : skillsByActorId.getOrDefault(combatActor.actorId(), List.of());

        System.out.printf("heavy2.fight6.drg.local64ac actor=%s%n", actor.localName());
        fullLocalSkills.stream()
                .filter(skill -> {
                    Integer guid = extractLocalSkillId(skill.skillName());
                    return guid != null && guid == 0x64AC;
                })
                .sorted((a, b) -> Long.compare(b.totalDamage(), a.totalDamage()))
                .forEach(skill -> System.out.printf(
                        "  skillName=%s total=%d hits=%d%n",
                        skill.skillName(),
                        skill.totalDamage(),
                        skill.hitCount()
                ));
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
    void debugHeavy2Fight2ActorDeltas_printsSamWhmSchDrgTotals() throws Exception {
        printActorParityComparisons(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2,
                List.of("Samurai", "WhiteMage", "Scholar", "Dragoon"),
                "heavy2.fight2.actorTotals.extended"
        );
    }

    @Test
    void debugHeavy4Fight5ActorDeltas_printsTopJobs() throws Exception {
        printActorParityComparisons(
                "2026-03-15-heavy4-vafpbaqjnhbk1mtw",
                5,
                List.of("DarkKnight", "Paladin", "Dragoon", "Ninja", "Dancer", "Scholar", "Sage", "Pictomancer"),
                "heavy4.fight5.actorTotals"
        );
    }

    @Test
    void debugLindwurmFight8ActorDeltas_printsTopJobs() throws Exception {
        printActorParityComparisons(
                "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz",
                8,
                List.of("DarkKnight", "Dragoon", "Ninja", "Dancer", "Scholar", "Astrologian", "Pictomancer"),
                "lindwurm.fight8.actorTotals"
        );
    }

    @Test
    void debugHeavy2Fight2SamuraiParityBreakdown_printsRdpsComponents() throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(
                "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt",
                2
        );
        assertEquals("ok", report.fflogs().status());
        assertEquals(2, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> "Samurai".equals(c.fflogsType()))
                .findFirst()
                .orElseThrow();

        System.out.printf(
                "heavy2.fight2.sam parityBreakdown actor=%s localDps=%.1f localReceived=%.1f localGranted=%.1f localExternal=%.1f localDerived=%.1f localOnline=%.1f fflogsDps=%.1f fflogsTaken=%.1f fflogsGiven=%.1f fflogsExternal=%.1f fflogsRdps=%.1f totalDelta=%.1f receivedDelta=%.1f grantedDelta=%.1f externalDelta=%.1f derivedDelta=%.1f rdpsDelta=%.1f%n",
                comparison.localName(),
                comparison.localDpsPerSecond(),
                comparison.localReceivedBuffPerSecond(),
                comparison.localGrantedBuffPerSecond(),
                comparison.localExternalDeltaPerSecond(),
                comparison.localDerivedRdpsPerSecond(),
                comparison.localOnlineRdps(),
                comparison.fflogsDpsPerSecond(),
                comparison.fflogsRdpsTakenPerSecond(),
                comparison.fflogsRdpsGivenPerSecond(),
                comparison.fflogsExternalDeltaPerSecond(),
                comparison.fflogsRdpsPerSecond(),
                comparison.totalDamageDelta(),
                comparison.receivedDeltaPerSecond(),
                comparison.grantedDeltaPerSecond(),
                comparison.externalDeltaPerSecond(),
                comparison.derivedRdpsDelta(),
                comparison.rdpsDelta()
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

    private static String exactComponentState(Integer actionExact, Integer statusExact, int expectedGuid) {
        boolean actionMatches = actionExact != null && actionExact == expectedGuid;
        boolean statusMatches = statusExact != null && statusExact == expectedGuid;
        if (actionMatches && statusMatches) {
            return "corroborated";
        }
        if (actionMatches) {
            return "action_only";
        }
        if (statusMatches) {
            return "status_only";
        }
        if (actionExact == null && statusExact == null) {
            return "none";
        }
        return "mismatch";
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

        Optional<String> resolvedActorName = report.comparisons().stream()
                .filter(comparison -> actorName.equals(comparison.localName()))
                .map(SubmissionParityReport.ActorParityComparison::localName)
                .findFirst()
                .or(() -> report.comparisons().stream()
                        .filter(comparison -> actorName.equals(comparison.fflogsType()))
                        .map(SubmissionParityReport.ActorParityComparison::localName)
                        .findFirst());

        OptionalLong actorId = resolvedActorName.isPresent()
                ? report.combat().actors().stream()
                .filter(actor -> resolvedActorName.get().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                : OptionalLong.empty();

        String actionHex = Integer.toHexString(actionId).toUpperCase();
        String sourceHex = actorId.isPresent()
                ? Long.toHexString(actorId.getAsLong()).toUpperCase()
                : null;
        Map<String, Long> assignedAmounts = ingestion.debugDotAttributionEmittedAmounts();
        Map<String, Long> assignedHits = ingestion.debugDotAttributionEmittedHitCounts();

        System.out.printf(
                "%s emittedDotModeBreakdown fight=%d actor=%s action=%s%n",
                label,
                fightId,
                resolvedActorName.orElse(actorName),
                actionHex
        );
        assignedAmounts.entrySet().stream()
                .filter(entry -> entry.getKey().contains("action=" + actionHex))
                .filter(entry -> sourceHex == null || entry.getKey().contains("source=" + sourceHex))
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> System.out.printf(
                        "  mode=%s amount=%d hits=%d%n",
                        entry.getKey(),
                        entry.getValue(),
                        assignedHits.getOrDefault(entry.getKey(), 0L)
                ));
    }

    private void printDotAttributionBreakdownByTarget(
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

        Optional<String> resolvedActorName = report.comparisons().stream()
                .filter(comparison -> actorName.equals(comparison.localName()))
                .map(SubmissionParityReport.ActorParityComparison::localName)
                .findFirst()
                .or(() -> report.comparisons().stream()
                        .filter(comparison -> actorName.equals(comparison.fflogsType()))
                        .map(SubmissionParityReport.ActorParityComparison::localName)
                        .findFirst());

        OptionalLong actorId = resolvedActorName.isPresent()
                ? report.combat().actors().stream()
                .filter(actor -> resolvedActorName.get().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                : OptionalLong.empty();

        String actionHex = Integer.toHexString(actionId).toUpperCase();
        String sourceHex = actorId.isPresent()
                ? Long.toHexString(actorId.getAsLong()).toUpperCase()
                : null;
        Map<String, Long> assignedAmounts = ingestion.debugDotAttributionEmittedAmounts();
        Map<String, Long> assignedHits = ingestion.debugDotAttributionEmittedHitCounts();

        System.out.printf(
                "%s emittedDotModeByTarget fight=%d actor=%s action=%s%n",
                label,
                fightId,
                resolvedActorName.orElse(actorName),
                actionHex
        );
        assignedAmounts.entrySet().stream()
                .filter(entry -> entry.getKey().contains("action=" + actionHex))
                .filter(entry -> sourceHex == null || entry.getKey().contains("source=" + sourceHex))
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> {
                    String targetHex = extractDebugAssignmentField(entry.getKey(), "target=");
                    long targetId = targetHex == null ? 0L : Long.parseUnsignedLong(targetHex, 16);
                    System.out.printf(
                            "  target=%s(%s) mode=%s amount=%d hits=%d%n",
                            targetHex == null ? "?" : targetHex,
                            targetNameById.getOrDefault(targetId, "?"),
                            entry.getKey(),
                            entry.getValue(),
                            assignedHits.getOrDefault(entry.getKey(), 0L)
                    );
                });
    }

    private void printActorGuidTotals(
            String submissionId,
            int fightId,
            String actorName,
            List<Integer> guidIds,
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

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorName.equals(c.fflogsType()) || actorName.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.AbilityDamageEntry> abilityTable = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId()
        );

        System.out.printf(
                "%s guidTotals fight=%d actor=%s localName=%s fflogsActorId=%d%n",
                label,
                fightId,
                actorName,
                comparison.localName(),
                comparison.fflogsActorId()
        );
        for (int guid : guidIds) {
            long localTotal = capturedDamageEvents.stream()
                    .filter(e -> e.sourceId().value() == localActorId)
                    .filter(e -> e.actionId() == guid)
                    .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                    .sum();
            long localHits = capturedDamageEvents.stream()
                    .filter(e -> e.sourceId().value() == localActorId)
                    .filter(e -> e.actionId() == guid)
                    .count();
            long fflogsAbilityTotal = abilityTable.stream()
                    .filter(a -> a.guid() != null && a.guid() == guid)
                    .mapToLong(a -> Math.round(a.total()))
                    .sum();
            List<FflogsApiClient.DamageEventEntry> fflogsEvents = apiClient.fetchDamageDoneEventsByAbility(
                    report.fflogs().reportCode(),
                    report.fflogs().selectedFightId(),
                    comparison.fflogsActorId(),
                    guid
            );
            long fflogsEventTotal = fflogsEvents.stream()
                    .mapToLong(FflogsApiClient.DamageEventEntry::amount)
                    .sum();
            System.out.printf(
                    "  guid=%s localTotal=%d localHits=%d fflogsAbilityTotal=%d fflogsEventTotal=%d fflogsEventHits=%d deltaVsAbility=%d%n",
                    formatGuid(guid),
                    localTotal,
                    localHits,
                    fflogsAbilityTotal,
                    fflogsEventTotal,
                    fflogsEvents.size(),
                    localTotal - fflogsAbilityTotal
            );
        }
    }

    private void printActorFflogsAbilities(
            String submissionId,
            int fightId,
            String actorName,
            List<String> nameHints,
            Set<Integer> guidHints,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorName.equals(c.fflogsType()) || actorName.equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.AbilityDamageEntry> abilityTable = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId()
        );

        System.out.printf(
                "%s fflogsAbilities fight=%d actor=%s localName=%s fflogsActorId=%d%n",
                label,
                fightId,
                actorName,
                comparison.localName(),
                comparison.fflogsActorId()
        );
        abilityTable.stream()
                .filter(ability -> {
                    Integer guid = ability.guid();
                    if (guid != null && guidHints.contains(guid)) {
                        return true;
                    }
                    String name = ability.name() == null ? "" : ability.name();
                    return nameHints.stream().anyMatch(name::contains);
                })
                .sorted((left, right) -> Double.compare(right.total(), left.total()))
                .forEach(ability -> System.out.printf(
                        "  guid=%s name=%s type=%s total=%.0f%n",
                        ability.guid() == null ? "null" : formatGuid(ability.guid()),
                        ability.name(),
                        ability.type(),
                        ability.total()
                ));
    }

    private void printModeBreakdownForSource(
            String submissionId,
            int fightId,
            String actorName,
            String modePrefix,
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
                .filter(comparison -> actorName.equals(comparison.localName()) || actorName.equals(comparison.fflogsType()))
                .map(SubmissionParityReport.ActorParityComparison::localName)
                .findFirst()
                .orElseThrow();
        long actorId = report.combat().actors().stream()
                .filter(actor -> resolvedActorName.equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        String sourceHex = Long.toHexString(actorId).toUpperCase();
        Map<String, Long> emittedAmounts = ingestion.debugDotAttributionEmittedAmounts();
        Map<String, Long> emittedHits = ingestion.debugDotAttributionEmittedHitCounts();

        System.out.printf(
                "%s modeBreakdown fight=%d actor=%s mode=%s%n",
                label,
                fightId,
                resolvedActorName,
                modePrefix
        );
        emittedAmounts.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(modePrefix + "|"))
                .filter(entry -> entry.getKey().contains("source=" + sourceHex))
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> System.out.printf(
                        "  %s amount=%d hits=%d%n",
                        entry.getKey(),
                        entry.getValue(),
                        emittedHits.getOrDefault(entry.getKey(), 0L)
                ));
    }

    @SuppressWarnings("unchecked")
    private void printWeightedTrackedTargetSplitEntryEvidence(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(
                        ingestion,
                        dot,
                        15_000L
                );
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String weightedKey = dotAttributionAssignmentKey(
                        "status0_weighted_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionEmittedAmounts().getOrDefault(weightedKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionEmittedAmounts().getOrDefault(weightedKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                String bucket = "activeTargets=%d|recentExact=%s|trackedCount=%d|sourceTrackedCount=%d|tracked=%s|sourceTracked=%s".formatted(
                        activeTargets,
                        formatGuid(recentExactActionId),
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(",")),
                        trackedSourceDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(","))
                );
                String sample = "target=%s assigned=%d".formatted(dot.targetName(), assignedDelta);
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s weightedEntryEvidence fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printWeightedTrackedTargetSplitRawContributors(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot && dot.statusId() == 0 && dot.damage() > 0) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(
                        ingestion,
                        dot,
                        15_000L
                );
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String weightedKey = dotAttributionAssignmentKey(
                        "status0_weighted_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionEmittedAmounts().getOrDefault(weightedKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionEmittedAmounts().getOrDefault(weightedKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                String bucket = "rawSource=%s(%s)|target=%s|activeTargets=%d|recentExact=%s|tracked=%s|sourceTracked=%s".formatted(
                        dot.sourceName(),
                        Long.toHexString(dot.sourceId()).toUpperCase(),
                        dot.targetName(),
                        activeTargets,
                        formatGuid(recentExactActionId),
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(",")),
                        trackedSourceDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(","))
                );
                String sample = "assigned=%d rawDamage=%d".formatted(assignedDelta, dot.damage());
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            return stats.add(assignedDelta, sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s weightedRawContributors fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d assigned=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printWeightedTrackedTargetSplitActionRecipients(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);

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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(
                        ingestion,
                        dot,
                        15_000L
                );
                ingestion.debugDotAttributionEmittedAmounts();
                Map<String, Long> before = new HashMap<>(ingestion.debugDotAttributionEmittedAmounts());
                ingestion.onParsed(parsed);
                Map<String, Long> after = ingestion.debugDotAttributionEmittedAmounts();

                for (Map.Entry<String, Long> entry : after.entrySet()) {
                    if (!entry.getKey().startsWith("status0_weighted_tracked_target_split|")) {
                        continue;
                    }
                    long delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
                    if (delta <= 0L) {
                        continue;
                    }

                    String bucket = "rawTarget=%s|recentExact=%s|assignedAction=%s|assignedTarget=%s".formatted(
                            dot.targetName(),
                            formatGuid(recentExactActionId),
                            extractDebugAssignmentField(entry.getKey(), "action="),
                            extractDebugAssignmentField(entry.getKey(), "target=")
                    );
                    String sample = "rawDamage=%d".formatted(dot.damage());
                    statsByBucket.compute(
                            bucket,
                            (ignored, existing) -> {
                                StructureStats stats = existing == null ? new StructureStats() : existing;
                                return stats.add(delta, sample);
                            }
                    );
                }
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s weightedActionRecipients fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(20)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d assigned=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    private void printActorGuidDirectVsDot(
            String submissionId,
            int fightId,
            String actorName,
            int guid,
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

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorName.equals(c.fflogsType()) || actorName.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        long emittedTotal = capturedDamageEvents.stream()
                .filter(e -> e.sourceId().value() == localActorId)
                .filter(e -> e.actionId() == guid)
                .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                .sum();
        long emittedHits = capturedDamageEvents.stream()
                .filter(e -> e.sourceId().value() == localActorId)
                .filter(e -> e.actionId() == guid)
                .count();

        long rawDirectTotal = 0L;
        long rawDirectHits = 0L;
        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (!(parsed instanceof NetworkAbilityRaw ability)) {
                continue;
            }
            if (ability.actorId() != localActorId || ability.skillId() != guid) {
                continue;
            }
            rawDirectTotal += ability.damage();
            rawDirectHits++;
        }

        FflogsApiClient apiClient = buildConfiguredApiClient();
        long fflogsAbilityTotal = apiClient.fetchDamageDoneAbilities(
                        report.fflogs().reportCode(),
                        report.fflogs().selectedFightId(),
                        comparison.fflogsActorId()
                ).stream()
                .filter(a -> a.guid() != null && a.guid() == guid)
                .mapToLong(a -> Math.round(a.total()))
                .sum();

        System.out.printf(
                "%s directVsDot fight=%d actor=%s guid=%s emittedTotal=%d emittedHits=%d raw21Total=%d raw21Hits=%d inferredDotTotal=%d inferredDotHits=%d fflogsAbilityTotal=%d delta=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                emittedTotal,
                emittedHits,
                rawDirectTotal,
                rawDirectHits,
                emittedTotal - rawDirectTotal,
                emittedHits - rawDirectHits,
                fflogsAbilityTotal,
                emittedTotal - fflogsAbilityTotal
        );
    }

    private void printStatus0TargetSourceBreakdown(
            String submissionId,
            int fightId,
            String actorName,
            int guid,
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

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorName.equals(c.fflogsType()) || actorName.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Map<String, Long> assignedAmounts = ingestion.debugDotAttributionAssignedAmounts();
        Map<Long, Map<String, Long>> modeTotalsByTarget = new HashMap<>();
        for (Map.Entry<String, Long> entry : assignedAmounts.entrySet()) {
            String key = entry.getKey();
            String mode = extractDebugAssignmentField(key, "");
            String sourceHex = extractDebugAssignmentField(key, "source=");
            String targetHex = extractDebugAssignmentField(key, "target=");
            String actionHex = extractDebugAssignmentField(key, "action=");
            if (mode == null || !mode.startsWith("status0_")
                    || sourceHex == null || targetHex == null || actionHex == null) {
                continue;
            }
            try {
                long sourceId = Long.parseUnsignedLong(sourceHex, 16);
                long targetId = Long.parseUnsignedLong(targetHex, 16);
                int actionId = Integer.parseInt(actionHex, 16);
                if (sourceId != localActorId || actionId != guid) {
                    continue;
                }
                modeTotalsByTarget
                        .computeIfAbsent(targetId, ignored -> new HashMap<>())
                        .merge(mode, entry.getValue(), Long::sum);
            } catch (NumberFormatException ignored) {
            }
        }

        Map<Long, String> targetNames = new HashMap<>();
        Map<Long, Map<String, Long>> rawStatus0ByTargetSource = new HashMap<>();
        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (!(parsed instanceof DotTickRaw dot) || !dot.isDot() || dot.statusId() != 0 || dot.damage() <= 0) {
                continue;
            }
            if (!modeTotalsByTarget.containsKey(dot.targetId())) {
                continue;
            }
            targetNames.putIfAbsent(dot.targetId(), dot.targetName());
            String sourceKey = dot.sourceName() + "(" + Long.toHexString(dot.sourceId()).toUpperCase() + ")";
            rawStatus0ByTargetSource
                    .computeIfAbsent(dot.targetId(), ignored -> new HashMap<>())
                    .merge(sourceKey, dot.damage(), Long::sum);
        }

        System.out.printf(
                "%s status0TargetSources fight=%d actor=%s localName=%s guid=%s%n",
                label,
                fightId,
                actorName,
                comparison.localName(),
                formatGuid(guid)
        );
        modeTotalsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(
                        right.getValue().values().stream().mapToLong(Long::longValue).sum(),
                        left.getValue().values().stream().mapToLong(Long::longValue).sum()
                ))
                .limit(6)
                .forEach(entry -> {
                    long targetId = entry.getKey();
                    long localAssignedTotal = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
                    long localEmittedTotal = capturedDamageEvents.stream()
                            .filter(e -> e.sourceId().value() == localActorId)
                            .filter(e -> e.targetId().value() == targetId)
                            .filter(e -> e.actionId() == guid)
                            .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                            .sum();
                    String targetName = targetNames.getOrDefault(targetId, Long.toHexString(targetId).toUpperCase());
                    System.out.printf(
                            "  target=%s(%s) status0Assigned=%d localEmitted=%d modes=%s%n",
                            Long.toHexString(targetId).toUpperCase(),
                            targetName,
                            localAssignedTotal,
                            localEmittedTotal,
                            entry.getValue()
                    );
                    rawStatus0ByTargetSource.getOrDefault(targetId, Map.of()).entrySet().stream()
                            .sorted((leftSource, rightSource) -> Long.compare(rightSource.getValue(), leftSource.getValue()))
                            .limit(6)
                            .forEach(sourceEntry -> System.out.printf(
                                    "    raw24status0 source=%s total=%d%n",
                                    sourceEntry.getKey(),
                                    sourceEntry.getValue()
                    ));
                });
    }

    private void printActorGuidSurfaceByTarget(
            String submissionId,
            int fightId,
            String actorName,
            int guid,
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

        Map<Long, String> targetNameById = new HashMap<>();
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
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorName.equals(c.fflogsType()) || actorName.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        String actionHex = Integer.toHexString(guid).toUpperCase();
        String sourceHex = Long.toHexString(localActorId).toUpperCase();

        Map<Long, Long> directByTarget = new HashMap<>();
        Map<Long, Long> dotByTarget = new HashMap<>();
        for (com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent event : capturedDamageEvents) {
            if (event.sourceId().value() != localActorId || event.actionId() != guid) {
                continue;
            }
            Map<Long, Long> bucket = event.damageType() == DamageType.DOT ? dotByTarget : directByTarget;
            bucket.merge(event.targetId().value(), event.amount(), Long::sum);
        }

        Map<Long, Map<String, Long>> emittedByTargetMode = new HashMap<>();
        for (Map.Entry<String, Long> entry : ingestion.debugDotAttributionEmittedAmounts().entrySet()) {
            if (!entry.getKey().contains("action=" + actionHex) || !entry.getKey().contains("source=" + sourceHex)) {
                continue;
            }
            String targetHex = extractDebugAssignmentField(entry.getKey(), "target=");
            String mode = extractDebugAssignmentField(entry.getKey(), "");
            if (targetHex == null || mode == null) {
                continue;
            }
            long targetId = Long.parseUnsignedLong(targetHex, 16);
            emittedByTargetMode
                    .computeIfAbsent(targetId, ignored -> new HashMap<>())
                    .merge(mode, entry.getValue(), Long::sum);
        }

        Map<Long, Long> assignedStatus0ByTarget = new HashMap<>();
        for (Map.Entry<String, Long> entry : ingestion.debugDotAttributionAssignedAmounts().entrySet()) {
            if (!entry.getKey().contains("action=" + actionHex) || !entry.getKey().contains("source=" + sourceHex)) {
                continue;
            }
            String targetHex = extractDebugAssignmentField(entry.getKey(), "target=");
            if (targetHex == null) {
                continue;
            }
            long targetId = Long.parseUnsignedLong(targetHex, 16);
            assignedStatus0ByTarget.merge(targetId, entry.getValue(), Long::sum);
        }

        Set<Long> allTargets = new HashSet<>();
        allTargets.addAll(directByTarget.keySet());
        allTargets.addAll(dotByTarget.keySet());
        allTargets.addAll(emittedByTargetMode.keySet());
        allTargets.addAll(assignedStatus0ByTarget.keySet());

        System.out.printf(
                "%s guidSurfaceByTarget fight=%d actor=%s localName=%s guid=%s%n",
                label,
                fightId,
                actorName,
                comparison.localName(),
                formatGuid(guid)
        );
        allTargets.stream()
                .sorted((left, right) -> Long.compare(
                        directByTarget.getOrDefault(right, 0L) + dotByTarget.getOrDefault(right, 0L),
                        directByTarget.getOrDefault(left, 0L) + dotByTarget.getOrDefault(left, 0L)
                ))
                .forEach(targetId -> System.out.printf(
                        "  target=%s(%s) total=%d direct=%d dot=%d status0Assigned=%d emittedModes=%s%n",
                        Long.toHexString(targetId).toUpperCase(),
                        targetNameById.getOrDefault(targetId, "?"),
                        directByTarget.getOrDefault(targetId, 0L) + dotByTarget.getOrDefault(targetId, 0L),
                        directByTarget.getOrDefault(targetId, 0L),
                        dotByTarget.getOrDefault(targetId, 0L),
                        assignedStatus0ByTarget.getOrDefault(targetId, 0L),
                        emittedByTargetMode.getOrDefault(targetId, Map.of())
                ));
    }

    private static String extractDebugAssignmentField(String key, String prefix) {
        if (prefix.isEmpty()) {
            int end = key.indexOf('|');
            return end < 0 ? key : key.substring(0, end);
        }
        int start = key.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int from = start + prefix.length();
        int end = key.indexOf('|', from);
        if (end < 0) {
            end = key.length();
        }
        if (from >= end) {
            return null;
        }
        return key.substring(from, end);
    }

    private void printStatus0SnapshotTiming(
            String submissionId,
            int fightId,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        Map<Long, String> targetNames = new HashMap<>();
        Map<Long, List<Instant>> snapshotTimesByTarget = new HashMap<>();
        Map<Long, List<Instant>> status0TimesByTarget = new HashMap<>();

        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot) {
                if (dot.statusId() != 0 || !targetIds.contains(dot.targetId())) {
                    continue;
                }
                targetNames.putIfAbsent(dot.targetId(), dot.targetName());
                status0TimesByTarget.computeIfAbsent(dot.targetId(), ignored -> new ArrayList<>()).add(dot.ts());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
                if (!targetIds.contains(snapshot.actorId())) {
                    continue;
                }
                targetNames.putIfAbsent(snapshot.actorId(), snapshot.actorName());
                snapshotTimesByTarget.computeIfAbsent(snapshot.actorId(), ignored -> new ArrayList<>()).add(snapshot.ts());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantStatusSnapshotRaw snapshot) {
                if (!targetIds.contains(snapshot.actorId())) {
                    continue;
                }
                targetNames.putIfAbsent(snapshot.actorId(), snapshot.actorName());
                snapshotTimesByTarget.computeIfAbsent(snapshot.actorId(), ignored -> new ArrayList<>()).add(snapshot.ts());
            }
        }

        System.out.printf("%s snapshotTiming fight=%d%n", label, fightId);
        for (long targetId : targetIds) {
            List<Instant> status0Times = status0TimesByTarget.getOrDefault(targetId, List.of());
            List<Instant> snapshotTimes = snapshotTimesByTarget.getOrDefault(targetId, List.of());
            System.out.printf(
                    "  target=%s(%s) status0Count=%d snapshotCount=%d%n",
                    Long.toHexString(targetId).toUpperCase(),
                    targetNames.getOrDefault(targetId, "?"),
                    status0Times.size(),
                    snapshotTimes.size()
            );
            status0Times.stream().limit(6).forEach(status0Ts -> {
                Instant previous = snapshotTimes.stream()
                        .filter(snapshotTs -> !snapshotTs.isAfter(status0Ts))
                        .max(Instant::compareTo)
                        .orElse(null);
                Instant next = snapshotTimes.stream()
                        .filter(snapshotTs -> snapshotTs.isAfter(status0Ts))
                        .min(Instant::compareTo)
                        .orElse(null);
                long previousDelta = previous == null ? Long.MIN_VALUE : Duration.between(previous, status0Ts).toMillis();
                long nextDelta = next == null ? Long.MIN_VALUE : Duration.between(status0Ts, next).toMillis();
                System.out.printf(
                        "    tick=%s prevSnapshotDeltaMs=%s nextSnapshotDeltaMs=%s%n",
                        status0Ts,
                        previous == null ? "none" : Long.toString(previousDelta),
                        next == null ? "none" : Long.toString(nextDelta)
                );
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void printProblemTargetSnapshotWeights(
            String submissionId,
            int fightId,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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
        Map<Long, String> targetNames = new HashMap<>();
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }
            if (parsed instanceof DotTickRaw dot && targetIds.contains(dot.targetId())) {
                targetNames.putIfAbsent(dot.targetId(), dot.targetName());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot
                    && targetIds.contains(snapshot.actorId())) {
                targetNames.putIfAbsent(snapshot.actorId(), snapshot.actorName());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantStatusSnapshotRaw snapshot
                    && targetIds.contains(snapshot.actorId())) {
                targetNames.putIfAbsent(snapshot.actorId(), snapshot.actorName());
            }
            ingestion.onParsed(parsed);
        }

        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Map<Long, Object> latestSnapshotsByTarget = (Map<Long, Object>) latestSnapshotsField.get(ingestion);

        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);
        Map<Long, Map<Object, Object>> activeTargetDots = (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);

        System.out.printf("%s snapshotWeights fight=%d%n", label, fightId);
        for (long targetId : targetIds) {
            Object snapshotState = latestSnapshotsByTarget.get(targetId);
            if (snapshotState == null) {
                System.out.printf(
                        "  target=%s(%s) snapshot=none%n",
                        Long.toHexString(targetId).toUpperCase(),
                        targetNames.getOrDefault(targetId, "?")
                );
                continue;
            }

            Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
            Map<Object, Double> rawWeights = (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
            Map<Object, Double> partyWeights = new HashMap<>();
            for (Map.Entry<Object, Double> entry : rawWeights.entrySet()) {
                long sourceId = trackedKeySourceId(entry.getKey());
                if (report.combat().actors().stream().anyMatch(actor -> actor.actorId().value() == sourceId)) {
                    partyWeights.put(entry.getKey(), entry.getValue());
                }
            }

            Map<Object, Object> activeDots = activeTargetDots.getOrDefault(targetId, Map.of());
            double fallbackTotal = partyWeights.values().stream().mapToDouble(Double::doubleValue).sum();
            double activeTotal = partyWeights.entrySet().stream()
                    .filter(entry -> activeDots.containsKey(entry.getKey()))
                    .mapToDouble(Map.Entry::getValue)
                    .sum();
            System.out.printf(
                    "  target=%s(%s) snapshotTs=%s partyWeightCount=%d activeTrackedCount=%d activeCoverage=%.4f%n",
                    Long.toHexString(targetId).toUpperCase(),
                    targetNames.getOrDefault(targetId, "?"),
                    snapshotTs,
                    partyWeights.size(),
                    activeDots.size(),
                    fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal
            );
            partyWeights.entrySet().stream()
                    .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                    .limit(8)
                    .forEach(entry -> System.out.printf(
                            "    weight key=%s weight=%.6f active=%s%n",
                            trackedKeyString(entry.getKey()),
                            entry.getValue(),
                            activeDots.containsKey(entry.getKey())
                    ));
        }
    }

    @SuppressWarnings("unchecked")
    private void printProblemTargetSnapshotSelection(
            String submissionId,
            int fightId,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByKey = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && targetIds.contains(dot.targetId())) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Object> activeDots =
                                    activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                            Map<Object, Double> sameSourceWeights = new HashMap<>();
                            if (partyActorIds.contains(dot.sourceId())
                                    && dot.sourceId() != 0
                                    && dot.sourceId() != 0xE0000000L) {
                                for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                    if (trackedKeySourceId(entry.getKey()) == dot.sourceId()) {
                                        sameSourceWeights.put(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                            Map<Object, Double> activeWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (activeDots.containsKey(entry.getKey())) {
                                    activeWeights.put(entry.getKey(), entry.getValue());
                                }
                            }

                            double fallbackTotal = fallbackWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeTotal = activeWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;

                            String path;
                            if (!sameSourceWeights.isEmpty()) {
                                path = "same_source";
                            } else if (!activeWeights.isEmpty()
                                    && (activeWeights.size() == fallbackWeights.size()
                                    || (activeWeights.size() >= 2 && activeCoverage >= 0.50))) {
                                path = "active_subset";
                            } else {
                                path = "fallback";
                            }

                            String sourceKey = dot.sourceName() + "("
                                    + Long.toHexString(dot.sourceId()).toUpperCase() + ")";
                            String aggregateKey = "target=%s|source=%s|path=%s".formatted(
                                    Long.toHexString(dot.targetId()).toUpperCase(),
                                    sourceKey,
                                    path
                            );
                            statsByKey.compute(
                                    aggregateKey,
                                    (ignored, existing) -> {
                                        SnapshotSelectionStats stats =
                                                existing == null ? new SnapshotSelectionStats() : existing;
                                        return stats.add(
                                                dot.damage(),
                                                activeCoverage,
                                                fallbackWeights,
                                                activeWeights,
                                                sameSourceWeights
                                        );
                                    }
                            );
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s snapshotSelection fight=%d%n", label, fightId);
        statsByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d avgActiveCoverage=%.4f fallbackKeys=%d activeKeys=%d sameSourceKeys=%d topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.averageActiveCoverage(),
                            stats.maxFallbackKeyCount(),
                            stats.maxActiveKeyCount(),
                            stats.maxSameSourceKeyCount(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printProblemTargetSameSourceButTrackedSplit(
            String submissionId,
            int fightId,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByKey = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && targetIds.contains(dot.targetId())
                    && partyActorIds.contains(dot.sourceId())
                    && dot.sourceId() != 0
                    && dot.sourceId() != 0xE0000000L) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        Map<Object, Double> sameSourceWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                            if (trackedKeySourceId(entry.getKey()) == dot.sourceId()) {
                                sameSourceWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!sameSourceWeights.isEmpty()) {
                            String trackedTargetKey = dotAttributionAssignmentKey(
                                    "status0_tracked_target_split",
                                    dot.sourceId(),
                                    dot.targetId(),
                                    trackedKeyActionId(sameSourceWeights.keySet().iterator().next())
                            );
                            long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                                    .getOrDefault(trackedTargetKey, 0L);
                            ingestion.onParsed(parsed);
                            long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                                    .getOrDefault(trackedTargetKey, 0L);
                            long assignedDelta = assignedAfter - assignedBefore;
                            if (assignedDelta > 0L) {
                                Map<Object, Object> activeDots =
                                        activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                                double fallbackTotal = fallbackWeights.values().stream()
                                        .mapToDouble(Double::doubleValue)
                                        .sum();
                                double activeTotal = fallbackWeights.entrySet().stream()
                                        .filter(entry -> activeDots.containsKey(entry.getKey()))
                                        .mapToDouble(Map.Entry::getValue)
                                        .sum();
                                double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;
                                String key = "target=%s|source=%s(%s)|assigned=%d|activeCoverage=%.4f|sameSource=%s".formatted(
                                        Long.toHexString(dot.targetId()).toUpperCase(),
                                        dot.sourceName(),
                                        Long.toHexString(dot.sourceId()).toUpperCase(),
                                        assignedDelta,
                                        activeCoverage,
                                        sameSourceWeights.keySet().stream()
                                                .map(SubmissionParityReportDiagnostics::trackedKeyString)
                                                .sorted()
                                                .toList()
                                );
                                statsByKey.compute(
                                        key,
                                        (ignored, existing) -> {
                                            SnapshotSelectionStats stats =
                                                    existing == null ? new SnapshotSelectionStats() : existing;
                                            return stats.add(
                                                    dot.damage(),
                                                    activeCoverage,
                                                    fallbackWeights,
                                                    Map.of(),
                                                    sameSourceWeights
                                            );
                                        }
                                );
                                continue;
                            }
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s sameSourceButTrackedSplit fight=%d%n", label, fightId);
        statsByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(20)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d fallbackKeys=%d sameSourceKeys=%d topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.maxFallbackKeyCount(),
                            stats.maxSameSourceKeyCount(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorSameSourceTrackedSplitMix(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId =
                ActIngestionService.class.getDeclaredMethod("resolveRecentSourceUnknownStatusActionId", DotTickRaw.class);
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, SameSourceSplitMixStats> statsByKey = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                List<Object> sourceTrackedDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                if (!sourceTrackedDots.isEmpty()) {
                    List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                    Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                    Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                    long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                    Map<String, Long> assignedBefore = new HashMap<>(ingestion.debugDotAttributionAssignedAmounts());
                    ingestion.onParsed(parsed);
                    Map<String, Long> assignedAfter = ingestion.debugDotAttributionAssignedAmounts();

                    long totalSplitAssigned = sumModeTargetDelta(
                            assignedBefore,
                            assignedAfter,
                            "status0_tracked_target_split",
                            dot.targetId()
                    );
                    long sameSourceAssigned = assignedAfter.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            )
                            - assignedBefore.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            );
                    long foreignAssigned = totalSplitAssigned - sameSourceAssigned;
                    if (sameSourceAssigned > 0L && foreignAssigned > 0L) {
                        String key = "target=%s|activeTargets=%d|tracked=%d|sourceTracked=%d|recentSource=%s|recentExact=%s".formatted(
                                Long.toHexString(dot.targetId()).toUpperCase(),
                                activeTargets,
                                trackedTargetDots.size(),
                                sourceTrackedDots.size(),
                                formatGuid(recentSourceActionId),
                                formatGuid(recentExactActionId)
                        );
                        statsByKey.compute(
                                key,
                                (ignored, existing) -> {
                                    SameSourceSplitMixStats stats =
                                            existing == null ? new SameSourceSplitMixStats() : existing;
                                    return stats.add(dot.damage(), totalSplitAssigned, sameSourceAssigned, foreignAssigned);
                                }
                        );
                        continue;
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s sameSourceTrackedSplitMix fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid));
        statsByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().foreignAssignedTotal(), left.getValue().foreignAssignedTotal()))
                .limit(20)
                .forEach(entry -> {
                    SameSourceSplitMixStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d raw=%d split=%d sameSource=%d foreign=%d%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.rawDamageTotal(),
                            stats.totalSplitAssigned(),
                            stats.sameSourceAssignedTotal(),
                            stats.foreignAssignedTotal()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorForeignTrackedSplitContributors(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, SameSourceSplitMixStats> statsByForeign = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                List<Object> sourceTrackedDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                if (!sourceTrackedDots.isEmpty() && trackedTargetDots.size() > sourceTrackedDots.size()) {
                    long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                    Map<String, Long> assignedBefore = new HashMap<>(ingestion.debugDotAttributionAssignedAmounts());
                    ingestion.onParsed(parsed);
                    Map<String, Long> assignedAfter = ingestion.debugDotAttributionAssignedAmounts();

                    long sameSourceAssigned = assignedAfter.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            )
                            - assignedBefore.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            );
                    if (sameSourceAssigned <= 0L) {
                        continue;
                    }

                    for (Object trackedDotState : trackedTargetDots) {
                        long sourceId = trackedDotStateSourceId(trackedDotState);
                        int actionId = trackedDotStateActionId(trackedDotState);
                        if (sourceId == localActorId && actionId == guid) {
                            continue;
                        }
                        String assignmentKey = dotAttributionAssignmentKey(
                                "status0_tracked_target_split",
                                sourceId,
                                dot.targetId(),
                                actionId
                        );
                        long assignedDelta = assignedAfter.getOrDefault(assignmentKey, 0L)
                                - assignedBefore.getOrDefault(assignmentKey, 0L);
                        if (assignedDelta <= 0L) {
                            continue;
                        }
                        String bucket = "target=%s|activeTargets=%d|foreign=%s".formatted(
                                Long.toHexString(dot.targetId()).toUpperCase(),
                                activeTargets,
                                trackedDotStateString(trackedDotState)
                        );
                        statsByForeign.compute(
                                bucket,
                                (ignored, existing) -> {
                                    SameSourceSplitMixStats stats =
                                            existing == null ? new SameSourceSplitMixStats() : existing;
                                    return stats.add(dot.damage(), assignedDelta, sameSourceAssigned, assignedDelta);
                                }
                        );
                    }
                    continue;
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s foreignTrackedSplitContributors fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid));
        statsByForeign.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().foreignAssignedTotal(), left.getValue().foreignAssignedTotal()))
                .limit(20)
                .forEach(entry -> {
                    SameSourceSplitMixStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d raw=%d sameSource=%d foreign=%d%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.rawDamageTotal(),
                            stats.sameSourceAssignedTotal(),
                            stats.foreignAssignedTotal()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorForeignSplitBudgetByActiveTargets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, SameSourceSplitMixStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                List<Object> sourceTrackedDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                if (!sourceTrackedDots.isEmpty() && trackedTargetDots.size() > sourceTrackedDots.size()) {
                    long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                    Map<String, Long> assignedBefore = new HashMap<>(ingestion.debugDotAttributionAssignedAmounts());
                    ingestion.onParsed(parsed);
                    Map<String, Long> assignedAfter = ingestion.debugDotAttributionAssignedAmounts();

                    long totalSplitAssigned = sumModeTargetDelta(
                            assignedBefore,
                            assignedAfter,
                            "status0_tracked_target_split",
                            dot.targetId()
                    );
                    long sameSourceAssigned = assignedAfter.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            )
                            - assignedBefore.getOrDefault(
                                    dotAttributionAssignmentKey("status0_tracked_target_split", localActorId, dot.targetId(), guid),
                                    0L
                            );
                    long foreignAssigned = totalSplitAssigned - sameSourceAssigned;
                    if (foreignAssigned > 0L) {
                        String bucket = "target=%s|activeTargets=%d|tracked=%d".formatted(
                                Long.toHexString(dot.targetId()).toUpperCase(),
                                activeTargets,
                                trackedTargetDots.size()
                        );
                        statsByBucket.compute(
                                bucket,
                                (ignored, existing) -> {
                                    SameSourceSplitMixStats stats =
                                            existing == null ? new SameSourceSplitMixStats() : existing;
                                    return stats.add(dot.damage(), totalSplitAssigned, sameSourceAssigned, foreignAssigned);
                                }
                        );
                        continue;
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s foreignSplitBudgetByActiveTargets fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid));
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().foreignAssignedTotal(), left.getValue().foreignAssignedTotal()))
                .limit(20)
                .forEach(entry -> {
                    SameSourceSplitMixStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d raw=%d split=%d sameSource=%d foreign=%d%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.rawDamageTotal(),
                            stats.totalSplitAssigned(),
                            stats.sameSourceAssignedTotal(),
                            stats.foreignAssignedTotal()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printSnapshotSelectionBySourceClass(
            String submissionId,
            int fightId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByKey = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && !dot.targetName().isBlank()) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Object> activeDots =
                                    activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                            Map<Object, Double> sameSourceWeights = new HashMap<>();
                            if (partyActorIds.contains(dot.sourceId())
                                    && dot.sourceId() != 0
                                    && dot.sourceId() != 0xE0000000L) {
                                for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                    if (trackedKeySourceId(entry.getKey()) == dot.sourceId()) {
                                        sameSourceWeights.put(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                            Map<Object, Double> activeWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (activeDots.containsKey(entry.getKey())) {
                                    activeWeights.put(entry.getKey(), entry.getValue());
                                }
                            }

                            double fallbackTotal = fallbackWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeTotal = activeWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;

                            String path;
                            if (!sameSourceWeights.isEmpty()) {
                                path = "same_source";
                            } else if (!activeWeights.isEmpty()
                                    && (activeWeights.size() == fallbackWeights.size()
                                    || (activeWeights.size() >= 2 && activeCoverage >= 0.50))) {
                                path = "active_subset";
                            } else {
                                path = "fallback";
                            }

                            String sourceClass = classifyStatus0Source(dot.sourceId(), partyActorIds);
                            String aggregateKey = "sourceClass=%s|path=%s".formatted(sourceClass, path);
                            statsByKey.compute(
                                    aggregateKey,
                                    (ignored, existing) -> {
                                        SnapshotSelectionStats stats =
                                                existing == null ? new SnapshotSelectionStats() : existing;
                                        return stats.add(
                                                dot.damage(),
                                                activeCoverage,
                                                fallbackWeights,
                                                activeWeights,
                                                sameSourceWeights
                                        );
                                    }
                            );
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s snapshotSelectionBySourceClass fight=%d%n", label, fightId);
        statsByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d avgActiveCoverage=%.4f fallbackKeys=%d activeKeys=%d sameSourceKeys=%d topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.averageActiveCoverage(),
                            stats.maxFallbackKeyCount(),
                            stats.maxActiveKeyCount(),
                            stats.maxSameSourceKeyCount(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printUnknownSnapshotSelectionTargets(
            String submissionId,
            int fightId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByTarget = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == 0xE0000000L) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Object> activeDots =
                                    activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                            Map<Object, Double> activeWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (activeDots.containsKey(entry.getKey())) {
                                    activeWeights.put(entry.getKey(), entry.getValue());
                                }
                            }

                            double fallbackTotal = fallbackWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeTotal = activeWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;
                            String path = (!activeWeights.isEmpty()
                                    && (activeWeights.size() == fallbackWeights.size()
                                    || (activeWeights.size() >= 2 && activeCoverage >= 0.50)))
                                    ? "active_subset"
                                    : "fallback";

                            String aggregateKey = "target=%s(%s)|path=%s".formatted(
                                    Long.toHexString(dot.targetId()).toUpperCase(),
                                    dot.targetName(),
                                    path
                            );
                            statsByTarget.compute(
                                    aggregateKey,
                                    (ignored, existing) -> {
                                        SnapshotSelectionStats stats =
                                                existing == null ? new SnapshotSelectionStats() : existing;
                                        return stats.add(
                                                dot.damage(),
                                                activeCoverage,
                                                fallbackWeights,
                                                activeWeights,
                                                Map.of()
                                        );
                                    }
                            );
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s unknownSnapshotSelectionTargets fight=%d%n", label, fightId);
        statsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d avgActiveCoverage=%.4f fallbackKeys=%d activeKeys=%d topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.averageActiveCoverage(),
                            stats.maxFallbackKeyCount(),
                            stats.maxActiveKeyCount(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printPartyActiveSubsetTargets(
            String submissionId,
            int fightId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByTarget = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && partyActorIds.contains(dot.sourceId())
                    && dot.sourceId() != 0xE0000000L) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Object> activeDots =
                                    activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                            Map<Object, Double> sameSourceWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (trackedKeySourceId(entry.getKey()) == dot.sourceId()) {
                                    sameSourceWeights.put(entry.getKey(), entry.getValue());
                                }
                            }
                            if (!sameSourceWeights.isEmpty()) {
                                if (parsed != null) {
                                    ingestion.onParsed(parsed);
                                }
                                continue;
                            }
                            Map<Object, Double> activeWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (activeDots.containsKey(entry.getKey())) {
                                    activeWeights.put(entry.getKey(), entry.getValue());
                                }
                            }

                            double fallbackTotal = fallbackWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeTotal = activeWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;
                            boolean activeSubset = !activeWeights.isEmpty()
                                    && (activeWeights.size() == fallbackWeights.size()
                                    || (activeWeights.size() >= 2 && activeCoverage >= 0.50));
                            if (activeSubset) {
                                String aggregateKey = "target=%s(%s)".formatted(
                                        Long.toHexString(dot.targetId()).toUpperCase(),
                                        dot.targetName()
                                );
                                statsByTarget.compute(
                                        aggregateKey,
                                        (ignored, existing) -> {
                                            SnapshotSelectionStats stats =
                                                    existing == null ? new SnapshotSelectionStats() : existing;
                                            return stats.add(
                                                    dot.damage(),
                                                    activeCoverage,
                                                    fallbackWeights,
                                                    activeWeights,
                                                    sameSourceWeights
                                            );
                                        }
                                );
                            }
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s partyActiveSubsetTargets fight=%d%n", label, fightId);
        statsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d avgActiveCoverage=%.4f fallbackKeys=%d activeKeys=%d topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.averageActiveCoverage(),
                            stats.maxFallbackKeyCount(),
                            stats.maxActiveKeyCount(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorActiveSubsetLeak(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByTarget = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Double> sameSourceWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (trackedKeySourceId(entry.getKey()) == dot.sourceId()) {
                                    sameSourceWeights.put(entry.getKey(), entry.getValue());
                                }
                            }
                            if (sameSourceWeights.isEmpty()) {
                                Map<Object, Object> activeDots =
                                        activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                                Map<Object, Double> activeWeights = new HashMap<>();
                                for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                    if (activeDots.containsKey(entry.getKey())) {
                                        activeWeights.put(entry.getKey(), entry.getValue());
                                    }
                                }
                                double fallbackTotal = fallbackWeights.values().stream()
                                        .mapToDouble(Double::doubleValue)
                                        .sum();
                                double activeTotal = activeWeights.values().stream()
                                        .mapToDouble(Double::doubleValue)
                                        .sum();
                                double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;
                                boolean activeSubset = !activeWeights.isEmpty()
                                        && (activeWeights.size() == fallbackWeights.size()
                                        || (activeWeights.size() >= 2 && activeCoverage >= 0.50));
                                if (activeSubset) {
                                    boolean guidPresent = false;
                                    for (Object keyObject : fallbackWeights.keySet()) {
                                        if (trackedKeySourceId(keyObject) == localActorId
                                                && trackedKeyActionId(keyObject) == guid) {
                                            guidPresent = true;
                                            break;
                                        }
                                    }
                                    List<String> sameSourceActiveKeys = activeDots.keySet().stream()
                                            .filter(keyObject -> {
                                                try {
                                                    return trackedKeySourceId(keyObject) == dot.sourceId();
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                            })
                                            .map(SubmissionParityReportDiagnostics::trackedKeyString)
                                            .sorted()
                                            .toList();
                                    List<String> topActiveKeys = activeWeights.entrySet().stream()
                                            .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                                            .limit(4)
                                            .<String>map(entry -> trackedKeyString(entry.getKey()) + "="
                                                    + String.format(Locale.US, "%.3f", entry.getValue()))
                                            .toList();
                                    String key = "target=%s(%s)|guidPresent=%s|sameSourceActive=%s|topActive=%s".formatted(
                                            Long.toHexString(dot.targetId()).toUpperCase(),
                                            dot.targetName(),
                                            guidPresent,
                                            sameSourceActiveKeys,
                                            topActiveKeys
                                    );
                                    statsByTarget.compute(
                                            key,
                                            (ignored, existing) -> {
                                                SnapshotSelectionStats stats =
                                                        existing == null ? new SnapshotSelectionStats() : existing;
                                                return stats.add(
                                                        dot.damage(),
                                                        activeCoverage,
                                                        fallbackWeights,
                                                        activeWeights,
                                                        sameSourceWeights
                                                );
                                            }
                                    );
                                }
                            }
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s actorActiveSubsetLeak fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d avgActiveCoverage=%.4f topFallback=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.averageActiveCoverage(),
                            stats.topFallbackKeys()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorAcceptedBySourcePotential(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        Method resolveDotActionId = ActIngestionService.class.getDeclaredMethod("resolveDotActionId", DotTickRaw.class);
        resolveDotActionId.setAccessible(true);
        Method shouldAcceptDot = ActIngestionService.class.getDeclaredMethod("shouldAcceptDot", DotTickRaw.class);
        shouldAcceptDot.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);

        Map<String, SnapshotSelectionStats> statsByTarget = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Map<Object, Double> snapshotWeights =
                            (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                    boolean guidPresent = false;
                    for (Object keyObject : snapshotWeights.keySet()) {
                        if (trackedKeySourceId(keyObject) == localActorId
                                && trackedKeyActionId(keyObject) == guid) {
                            guidPresent = true;
                            break;
                        }
                    }
                    if (!guidPresent) {
                        boolean accepted = (boolean) shouldAcceptDot.invoke(ingestion, dot);
                        int resolvedActionId = (int) resolveDotActionId.invoke(ingestion, dot);
                        if (accepted) {
                            String key = "target=%s(%s)|resolved=%s|matchesGuid=%s".formatted(
                                    Long.toHexString(dot.targetId()).toUpperCase(),
                                    dot.targetName(),
                                    formatGuid(resolvedActionId),
                                    resolvedActionId == guid
                            );
                            statsByTarget.compute(
                                    key,
                                    (ignored, existing) -> {
                                        SnapshotSelectionStats stats =
                                                existing == null ? new SnapshotSelectionStats() : existing;
                                        return stats.add(dot.damage(), 0.0, Map.of(), Map.of(), Map.of());
                                    }
                            );
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s acceptedBySourcePotential fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    SnapshotSelectionStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal()
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorTrackedTargetSplitEvidence(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        printActorTrackedTargetSplitEvidenceForTargets(submissionId, fightId, actorType, guid, null, label);
    }

    @SuppressWarnings("unchecked")
    private void printActorTrackedTargetSplitEvidenceForTargets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method shouldAcceptDot = ActIngestionService.class.getDeclaredMethod("shouldAcceptDot", DotTickRaw.class);
        shouldAcceptDot.setAccessible(true);
        Method shouldSuppressKnownSourceGuidMissingMultiTargetFallback = ActIngestionService.class.getDeclaredMethod(
                "shouldSuppressKnownSourceGuidMissingMultiTargetFallback",
                DotTickRaw.class
        );
        shouldSuppressKnownSourceGuidMissingMultiTargetFallback.setAccessible(true);
        Method shouldSuppressKnownSourceMismatchedTrackedTargetSplit = ActIngestionService.class.getDeclaredMethod(
                "shouldSuppressKnownSourceMismatchedTrackedTargetSplit",
                DotTickRaw.class,
                List.class
        );
        shouldSuppressKnownSourceMismatchedTrackedTargetSplit.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, TrackedTargetSplitEvidenceStats> statsBySignature = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId
                    && (targetIds == null || targetIds.contains(dot.targetId()))) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                boolean acceptedBySource = (boolean) shouldAcceptDot.invoke(ingestion, dot);
                boolean suppressKnownSourceGuidMissing = (boolean) shouldSuppressKnownSourceGuidMissingMultiTargetFallback
                        .invoke(ingestion, dot);
                boolean suppressKnownSourceMismatched = (boolean) shouldSuppressKnownSourceMismatchedTrackedTargetSplit
                        .invoke(ingestion, dot, trackedTargetDots);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long trackedTargetCount = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                boolean trackedTargetHasGuid = trackedTargetDots.stream().anyMatch(dotState -> {
                    try {
                        return trackedDotStateSourceId(dotState) == localActorId
                                && trackedDotStateActionId(dotState) == guid;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                boolean trackedSourceHasGuid = trackedSourceDots.stream().anyMatch(dotState -> {
                    try {
                        return trackedDotStateActionId(dotState) == guid;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                List<String> trackedTargetTop = trackedTargetDots.stream()
                        .limit(4)
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .toList();
                List<String> trackedSourceTop = trackedSourceDots.stream()
                        .limit(4)
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .toList();
                String signature = "target=%s(%s)|accepted=%s|sameSourceCount=%d|sameSourceHasGuid=%s|trackedCount=%d|trackedHasGuid=%s|recentExact=%s|recentSource=%s|suppressGuidMissing=%s|suppressMismatch=%s|activeTargets=%d|sameSource=%s|tracked=%s".formatted(
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        dot.targetName(),
                        acceptedBySource,
                        trackedSourceDots.size(),
                        trackedSourceHasGuid,
                        trackedTargetDots.size(),
                        trackedTargetHasGuid,
                        formatGuid(recentExactActionId),
                        formatGuid(recentSourceActionId),
                        suppressKnownSourceGuidMissing,
                        suppressKnownSourceMismatched,
                        trackedTargetCount,
                        trackedSourceTop,
                        trackedTargetTop
                );
                statsBySignature.compute(
                        signature,
                        (ignored, existing) -> {
                            TrackedTargetSplitEvidenceStats stats =
                                    existing == null ? new TrackedTargetSplitEvidenceStats() : existing;
                            return stats.add(dot.damage(), assignedDelta);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitEvidence fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsBySignature.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().assignedTotal(), left.getValue().assignedTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d rawDamage=%d assigned=%d%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().rawDamageTotal(),
                        entry.getValue().assignedTotal()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitContaminationBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                Set<Long> foreignSources = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateSourceId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(sourceId -> sourceId != localActorId)
                        .collect(Collectors.toCollection(TreeSet::new));
                Set<Integer> foreignActions = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(actionId -> actionId != guid)
                        .collect(Collectors.toCollection(TreeSet::new));
                boolean sameSourceHasGuid = trackedSourceDots.stream().anyMatch(dotState -> {
                    try {
                        return trackedDotStateActionId(dotState) == guid;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                String otherTargets = trackedTargetDots.stream()
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .filter(state -> !state.contains(":%s".formatted(formatGuid(guid))))
                        .limit(4)
                        .collect(Collectors.joining(", "));
                String bucket = "activeTargets=%d|trackedCount=%d|sameSourceCount=%d|sameSourceHasGuid=%s|foreignSources=%d|foreignActions=%d|recentExact=%s|recentSource=%s".formatted(
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        sameSourceHasGuid,
                        foreignSources.size(),
                        foreignActions.size(),
                        formatGuid(recentExactActionId),
                        formatGuid(recentSourceActionId)
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            String sample = stats.sampleOtherTargets().isBlank() ? otherTargets : stats.sampleOtherTargets();
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitBuckets fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sampleOther=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitCandidateCoverage(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        TrackedTargetSplitEvidenceStats total = new TrackedTargetSplitEvidenceStats();
        TrackedTargetSplitEvidenceStats candidate = new TrackedTargetSplitEvidenceStats();
        Map<String, StructureStats> candidateBuckets = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                total = total.add(dot.damage(), assignedDelta);

                Set<Integer> foreignActions = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(actionId -> actionId != guid)
                        .collect(Collectors.toCollection(TreeSet::new));
                boolean sameSourceHasGuid = trackedSourceDots.stream().anyMatch(dotState -> {
                    try {
                        return trackedDotStateActionId(dotState) == guid;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                boolean candidateHit = recentExactActionId == null
                        && trackedSourceDots.size() == 1
                        && sameSourceHasGuid
                        && foreignActions.size() >= 2;
                if (candidateHit) {
                    candidate = candidate.add(dot.damage(), assignedDelta);
                    String bucket = "activeTargets=%d|trackedCount=%d|foreignActions=%d|recentSource=%s".formatted(
                            activeTargets,
                            trackedTargetDots.size(),
                            foreignActions.size(),
                            formatGuid(recentSourceActionId)
                    );
                    candidateBuckets.compute(
                            bucket,
                            (ignored, existing) -> {
                                StructureStats stats = existing == null ? new StructureStats() : existing;
                                return stats.add(assignedDelta, "");
                            }
                    );
                }
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        double assignedShare = total.assignedTotal() == 0L
                ? 0.0
                : (candidate.assignedTotal() * 100.0) / total.assignedTotal();
        System.out.printf(
                "%s trackedTargetSplitCandidateCoverage fight=%d actor=%s guid=%s totalHits=%d totalAssigned=%d candidateHits=%d candidateAssigned=%d assignedShare=%.2f%%%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                total.hitCount(),
                total.assignedTotal(),
                candidate.hitCount(),
                candidate.assignedTotal(),
                assignedShare
        );
        candidateBuckets.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(8)
                .forEach(entry -> System.out.printf(
                        "  %s assigned=%d hits=%d%n",
                        entry.getKey(),
                        entry.getValue().damageTotal(),
                        entry.getValue().hitCount()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitCoverageMatrix(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        long totalAssigned = 0L;
        long totalHits = 0L;
        Map<String, StructureStats> matrix = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                totalAssigned += assignedDelta;
                totalHits += 1L;
                Set<Long> foreignSources = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateSourceId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(sourceId -> sourceId != localActorId)
                        .collect(Collectors.toCollection(TreeSet::new));
                Set<Integer> foreignActions = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(actionId -> actionId != guid)
                        .collect(Collectors.toCollection(TreeSet::new));
                String exactState;
                if (recentExactActionId == null) {
                    exactState = "null";
                } else if (recentExactActionId == guid) {
                    exactState = "same";
                } else {
                    exactState = "other";
                }
                String key = "exact=%s|activeTargets=%s|tracked=%d|foreignSources=%d|foreignActions=%d".formatted(
                        exactState,
                        activeTargets >= 3 ? "3+" : Long.toString(activeTargets),
                        trackedTargetDots.size(),
                        foreignSources.size(),
                        foreignActions.size()
                );
                matrix.compute(
                        key,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            return stats.add(assignedDelta, "");
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitCoverageMatrix fight=%d actor=%s guid=%s totalHits=%d totalAssigned=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                totalHits,
                totalAssigned
        );
        final long totalAssignedFinal = totalAssigned;
        matrix.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    long assigned = entry.getValue().damageTotal();
                    double share = totalAssignedFinal == 0L ? 0.0 : (assigned * 100.0) / totalAssignedFinal;
                    System.out.printf(
                            "  %s assigned=%d hits=%d share=%.2f%%%n",
                            entry.getKey(),
                            assigned,
                            entry.getValue().hitCount(),
                            share
                    );
                });
    }

    @SuppressWarnings("unchecked")
    private void printActorTrackedTargetSplitAssignedByActiveTargets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, Long> assignedByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }
                String bucket = "target=%s(%s)|activeTargets=%d".formatted(
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        dot.targetName(),
                        activeTargets
                );
                assignedByBucket.merge(bucket, assignedDelta, Long::sum);
                continue;
            }
            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitAssignedByActiveTargets fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        assignedByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .forEach(entry -> System.out.printf("  %s assigned=%d%n", entry.getKey(), entry.getValue()));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitEvidenceAgeBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);
        Field actionBySourceField = ActIngestionService.class.getDeclaredField("unknownStatusDotApplicationsBySource");
        actionBySourceField.setAccessible(true);
        Field statusBySourceField = ActIngestionService.class.getDeclaredField("unknownStatusDotStatusApplicationsBySource");
        statusBySourceField.setAccessible(true);
        Field actionEvidenceBySourceField = ActIngestionService.class.getDeclaredField("unknownStatusDotActionEvidenceBySource");
        actionEvidenceBySourceField.setAccessible(true);
        Field statusEvidenceBySourceField = ActIngestionService.class.getDeclaredField("unknownStatusDotStatusEvidenceBySource");
        statusEvidenceBySourceField.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, EvidenceAgeStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                Map<Long, Object> actionBySource =
                        (Map<Long, Object>) actionBySourceField.get(ingestion);
                Map<Long, Object> statusBySource =
                        (Map<Long, Object>) statusBySourceField.get(ingestion);
                Map<Long, Object> actionEvidenceBySource =
                        (Map<Long, Object>) actionEvidenceBySourceField.get(ingestion);
                Map<Long, Object> statusEvidenceBySource =
                        (Map<Long, Object>) statusEvidenceBySourceField.get(ingestion);

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                Object actionApplication = actionBySource.get(localActorId);
                Object statusApplication = statusBySource.get(localActorId);
                Object actionEvidence = actionEvidenceBySource.get(localActorId);
                Object statusEvidence = statusEvidenceBySource.get(localActorId);
                Long actionAgeMs = dotApplicationAgeMs(actionApplication, dot.ts());
                Long statusAgeMs = dotApplicationAgeMs(statusApplication, dot.ts());
                Long actionEvidenceTargetId = sourceEvidenceTargetId(actionEvidence);
                Long statusEvidenceTargetId = sourceEvidenceTargetId(statusEvidence);
                String bucket = "target=%s|activeTargets=%d|trackedCount=%d|foreignActions=%d|actionAge=%s|statusAge=%s|actionTarget=%s|statusTarget=%s".formatted(
                        dot.targetName(),
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedTargetDots.stream()
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .filter(state -> !state.endsWith(":%s".formatted(formatGuid(guid))))
                                .count(),
                        ageBucket(actionAgeMs),
                        ageBucket(statusAgeMs),
                        targetMatchBucket(dot.targetId(), actionEvidenceTargetId),
                        targetMatchBucket(dot.targetId(), statusEvidenceTargetId)
                );
                String sample = "current=%s actionTarget=%s statusTarget=%s tracked=%s".formatted(
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        formatTargetId(actionEvidenceTargetId),
                        formatTargetId(statusEvidenceTargetId),
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(", "))
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            EvidenceAgeStats stats = existing == null ? new EvidenceAgeStats() : existing;
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitEvidenceAge fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sample()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitExactComponentBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);
        Method toTrackedDotActionId = ActIngestionService.class.getDeclaredMethod("toTrackedDotActionId", int.class);
        toTrackedDotActionId.setAccessible(true);
        Field actionApplicationsField = ActIngestionService.class.getDeclaredField("unknownStatusDotApplications");
        actionApplicationsField.setAccessible(true);
        Field statusApplicationsField = ActIngestionService.class.getDeclaredField("unknownStatusDotStatusApplications");
        statusApplicationsField.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> actionApplications =
                        (Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication>) actionApplicationsField.get(ingestion);
                Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication> statusApplications =
                        (Map<UnknownStatusDotAttributionResolver.DotKey, UnknownStatusDotAttributionResolver.DotApplication>) statusApplicationsField.get(ingestion);

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                UnknownStatusDotAttributionResolver.DotKey exactKey =
                        new UnknownStatusDotAttributionResolver.DotKey(localActorId, dot.targetId());
                UnknownStatusDotAttributionResolver.DotApplication actionApplication = actionApplications.get(exactKey);
                UnknownStatusDotAttributionResolver.DotApplication statusApplication = statusApplications.get(exactKey);
                Integer actionExact = actionApplication == null ? null : actionApplication.actionId();
                Integer statusExact = statusApplication == null
                        ? null
                        : (Integer) toTrackedDotActionId.invoke(ingestion, statusApplication.actionId());
                String exactState = exactComponentState(actionExact, statusExact, guid);
                String bucket = "activeTargets=%d|trackedCount=%d|sameSourceCount=%d|foreignActions=%d|recentSource=%s|actionExact=%s|statusExact=%s|exactState=%s".formatted(
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        trackedTargetDots.stream()
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .filter(state -> !state.endsWith(":%s".formatted(formatGuid(guid))))
                                .count(),
                        formatGuid(recentSourceActionId),
                        formatGuid(actionExact),
                        formatGuid(statusExact),
                        exactState
                );
                String sample = "target=%s tracked=%s".formatted(
                        dot.targetName(),
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(", "))
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            String resolvedSample = stats.sampleOtherTargets().isBlank() ? sample : stats.sampleOtherTargets();
                            return stats.add(dot.damage(), resolvedSample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitExactComponent fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitSameActionTargetBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);
        Field actorNameByIdField = ActIngestionService.class.getDeclaredField("actorNameById");
        actorNameByIdField.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Map<Long, String> actorNameById =
                        (Map<Long, String>) actorNameByIdField.get(ingestion);

                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                List<Long> sameActionTargetIds = activeTargetDots.entrySet().stream()
                        .filter(entry -> entry.getValue().keySet().stream().anyMatch(keyObject -> {
                            try {
                                return trackedKeySourceId(keyObject) == dot.sourceId()
                                        && trackedKeyActionId(keyObject) == guid;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                String sameActionOtherTargets = sameActionTargetIds.stream()
                        .filter(targetId -> targetId != dot.targetId())
                        .map(targetId -> Long.toHexString(targetId).toUpperCase()
                                + "(" + actorNameById.getOrDefault(targetId, "?") + ")")
                        .collect(Collectors.joining(", "));
                long foreignActionsOnTarget = trackedTargetDots.stream()
                        .filter(dotState -> {
                            try {
                                return trackedDotStateSourceId(dotState) != dot.sourceId();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .mapToLong(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .distinct()
                        .count();
                String bucket = "activeTargets=%d|sameActionTargets=%d|trackedCount=%d|sameSourceCount=%d|foreignActions=%d|currentTarget=%s".formatted(
                        activeTargets,
                        sameActionTargetIds.size(),
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        foreignActionsOnTarget,
                        dot.targetName()
                );
                String sample = "sameActionOtherTargets=%s tracked=%s".formatted(
                        sameActionOtherTargets.isBlank() ? "-" : sameActionOtherTargets,
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(", "))
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitSameActionTargets fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitSameActionMultiTargetWindow(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        List<String> contextLines = new ArrayList<>();
        Deque<String> recentContext = new ArrayDeque<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            String summary = summarizeParsedLineForFallbackWindow(parsed);
            if (summary != null) {
                recentContext.addLast(summary);
                while (recentContext.size() > 12) {
                    recentContext.removeFirst();
                }
            }

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                String trackedTargetKey = dotAttributionAssignmentKey(
                        "status0_tracked_target_split",
                        localActorId,
                        dot.targetId(),
                        guid
                );
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(trackedTargetKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                List<Long> sameActionTargets = activeTargetDots.entrySet().stream()
                        .filter(entry -> entry.getValue().keySet().stream().anyMatch(keyObject -> {
                            try {
                                return trackedKeySourceId(keyObject) == dot.sourceId()
                                        && trackedKeyActionId(keyObject) == guid;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                if (sameActionTargets.size() < 2) {
                    continue;
                }

                String header = "tick=%s target=%s sameActionTargets=%s tracked=%s damage=%d".formatted(
                        dot.ts(),
                        dot.targetName(),
                        sameActionTargets.stream()
                                .map(targetId -> Long.toHexString(targetId).toUpperCase())
                                .collect(Collectors.joining(", ")),
                        trackedTargetDots.stream()
                                .limit(4)
                                .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                                .collect(Collectors.joining(", ")),
                        dot.damage()
                );
                contextLines.add(header + "\n    " + String.join("\n    ", recentContext));
                if (contextLines.size() >= 6) {
                    break;
                }
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitSameActionMultiTargetWindow fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        contextLines.forEach(window -> System.out.println("  " + window));
    }

    @SuppressWarnings("unchecked")
    private void printTrackedTargetSplitSameActionDualTargetLifecycle(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            int statusId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );

        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        List<String> lines = Files.readAllLines(combatLog, StandardCharsets.UTF_8);
        Instant sampleTs = null;
        List<Long> sampleTargets = List.of();
        long sampleDamage = 0L;

        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                long trackedTargetAssignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(dotAttributionAssignmentKey(
                                "status0_tracked_target_split",
                                localActorId,
                                dot.targetId(),
                                guid
                        ), 0L);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                ingestion.onParsed(parsed);
                long trackedTargetAssignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(dotAttributionAssignmentKey(
                                "status0_tracked_target_split",
                                localActorId,
                                dot.targetId(),
                                guid
                        ), 0L);
                if (trackedTargetAssignedAfter <= trackedTargetAssignedBefore) {
                    continue;
                }

                List<Long> sameActionTargets = activeTargetDots.entrySet().stream()
                        .filter(entry -> entry.getValue().keySet().stream().anyMatch(keyObject -> {
                            try {
                                return trackedKeySourceId(keyObject) == dot.sourceId()
                                        && trackedKeyActionId(keyObject) == guid;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                if (sameActionTargets.size() < 2) {
                    continue;
                }

                sampleTs = dot.ts();
                sampleTargets = sameActionTargets;
                sampleDamage = dot.damage();
                break;
            }

            ingestion.onParsed(parsed);
        }

        assertNotNull(sampleTs, "expected tracked_target_split sameActionTargets>=2 sample");
        Instant windowStart = sampleTs.minusSeconds(6);
        Instant windowEnd = sampleTs.plusSeconds(6);
        List<String> timeline = new ArrayList<>();

        for (String line : lines) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed == null) {
                continue;
            }

            Instant ts = extractParsedLineTimestamp(parsed);
            if (ts == null || ts.isBefore(windowStart) || ts.isAfter(windowEnd)) {
                continue;
            }

            String summary = summarizeSameActionDualTargetLifecycleLine(
                    parsed,
                    localActorId,
                    guid,
                    statusId,
                    sampleTargets
            );
            if (summary != null) {
                timeline.add(summary);
            }
        }

        System.out.printf(
                "%s trackedTargetSplitSameActionDualTargetLifecycle fight=%d actor=%s guid=%s status=%s sampleTs=%s sampleTargets=%s sampleDamage=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                formatGuid(statusId),
                sampleTs,
                sampleTargets.stream()
                        .map(targetId -> "%s(%s)".formatted(
                                Long.toHexString(targetId).toUpperCase(),
                                targetNameForId(report, targetId)
                        ))
                        .collect(Collectors.joining(", ")),
                sampleDamage
        );
        timeline.forEach(entry -> System.out.println("  " + entry));
    }

    @SuppressWarnings("unchecked")
    private void printSplitModeEvidenceBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String mode,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method shouldAcceptDot = ActIngestionService.class.getDeclaredMethod("shouldAcceptDot", DotTickRaw.class);
        shouldAcceptDot.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                boolean acceptedBySource = (boolean) shouldAcceptDot.invoke(ingestion, dot);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String assignmentKey = dotAttributionAssignmentKey(mode, localActorId, dot.targetId(), guid);
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(assignmentKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts()
                        .getOrDefault(assignmentKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                long sameDotSourceCount = trackedTargetDots.stream()
                        .filter(dotState -> {
                            try {
                                return trackedDotStateSourceId(dotState) == dot.sourceId();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .count();
                Set<Integer> foreignActions = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(actionId -> actionId != guid)
                        .collect(Collectors.toCollection(TreeSet::new));
                String sampleTracked = trackedTargetDots.stream()
                        .limit(4)
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .collect(Collectors.joining(", "));
                String bucket = "target=%s|dotSource=%s|accepted=%s|activeTargets=%d|trackedCount=%d|sourceTrackedCount=%d|sameDotSourceCount=%d|foreignActions=%d|recentExact=%s|recentSource=%s".formatted(
                        dot.targetName(),
                        dot.sourceId() == 0xE0000000L ? "unknown" : Long.toHexString(dot.sourceId()).toUpperCase(),
                        acceptedBySource,
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        sameDotSourceCount,
                        foreignActions.size(),
                        formatGuid(recentExactActionId),
                        formatGuid(recentSourceActionId)
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            String sample = stats.sampleOtherTargets().isBlank() ? sampleTracked : stats.sampleOtherTargets();
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s %s evidenceBuckets fight=%d actor=%s guid=%s%n",
                label,
                mode,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printFallbackSplitSourceLifecycleBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method shouldAcceptDot = ActIngestionService.class.getDeclaredMethod("shouldAcceptDot", DotTickRaw.class);
        shouldAcceptDot.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        Map<Long, String> actorNameById = new HashMap<>();
        Map<Long, Integer> actorJobById = new HashMap<>();
        Map<Long, Instant> lastSeenByTarget = new HashMap<>();
        Map<String, StructureStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded added) {
                actorNameById.putIfAbsent(added.id(), added.name());
                if (added.jobId() != 0) {
                    actorJobById.putIfAbsent(added.id(), added.jobId());
                }
            } else if (parsed instanceof NetworkAbilityRaw ability) {
                lastSeenByTarget.put(ability.targetId(), ability.ts());
            } else if (parsed instanceof BuffApplyRaw buffApply) {
                lastSeenByTarget.put(buffApply.targetId(), buffApply.ts());
            } else if (parsed instanceof BuffRemoveRaw buffRemove) {
                lastSeenByTarget.put(buffRemove.targetId(), buffRemove.ts());
            } else if (parsed instanceof DotTickRaw seenDot) {
                lastSeenByTarget.put(seenDot.targetId(), seenDot.ts());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
                lastSeenByTarget.put(snapshot.actorId(), snapshot.ts());
            }

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                boolean acceptedBySource = (boolean) shouldAcceptDot.invoke(ingestion, dot);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String assignmentKey = dotAttributionAssignmentKey("status0_fallback_tracked_target_split", localActorId, dot.targetId(), guid);
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                long sameDotSourceCount = trackedTargetDots.stream()
                        .filter(dotState -> {
                            try {
                                return trackedDotStateSourceId(dotState) == dot.sourceId();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .count();
                Set<Integer> foreignActions = trackedTargetDots.stream()
                        .map(dotState -> {
                            try {
                                return trackedDotStateActionId(dotState);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .filter(actionId -> actionId != guid)
                        .collect(Collectors.toCollection(TreeSet::new));
                Instant lastSeen = lastSeenByTarget.get(dot.targetId());
                long seenAgeMs = lastSeen == null ? Long.MAX_VALUE : Math.abs(Duration.between(lastSeen, dot.ts()).toMillis());
                String sourceName = actorNameById.getOrDefault(dot.sourceId(), dot.sourceName().isBlank() ? "?" : dot.sourceName());
                Integer sourceJob = actorJobById.get(dot.sourceId());
                String sampleTracked = trackedTargetDots.stream()
                        .limit(4)
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .collect(Collectors.joining(", "));
                String bucket = "target=%s|dotSource=%s(%s,%s)|accepted=%s|activeTargets=%d|trackedCount=%d|sourceTrackedCount=%d|sameDotSourceCount=%d|foreignActions=%d|recentExact=%s|recentSource=%s|targetSeenAge=%s".formatted(
                        dot.targetName(),
                        dot.sourceId() == 0xE0000000L ? "unknown" : Long.toHexString(dot.sourceId()).toUpperCase(),
                        sourceName,
                        formatJobId(sourceJob),
                        acceptedBySource,
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        sameDotSourceCount,
                        foreignActions.size(),
                        formatGuid(recentExactActionId),
                        formatGuid(recentSourceActionId),
                        ageBucket(seenAgeMs == Long.MAX_VALUE ? null : seenAgeMs)
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            StructureStats stats = existing == null ? new StructureStats() : existing;
                            String sample = stats.sampleOtherTargets().isBlank()
                                    ? "lastSeen=%s tracked=%s".formatted(lastSeen == null ? "null" : lastSeen, sampleTracked)
                                    : stats.sampleOtherTargets();
                            return stats.add(dot.damage(), sample);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s status0_fallback_tracked_target_split sourceLifecycle fight=%d actor=%s guid=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sampleOtherTargets()
                ));
    }

    @SuppressWarnings("unchecked")
    private void printSplitModeTargetSeenAgeBuckets(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            String mode,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);
        Method shouldAcceptDot = ActIngestionService.class.getDeclaredMethod("shouldAcceptDot", DotTickRaw.class);
        shouldAcceptDot.setAccessible(true);
        Method resolveRecentSourceUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentSourceUnknownStatusActionId",
                DotTickRaw.class
        );
        resolveRecentSourceUnknownStatusActionId.setAccessible(true);
        Method resolveRecentExactUnknownStatusActionId = ActIngestionService.class.getDeclaredMethod(
                "resolveRecentExactUnknownStatusActionId",
                DotTickRaw.class,
                long.class
        );
        resolveRecentExactUnknownStatusActionId.setAccessible(true);
        Method countTrackedTargetsWithActiveDots = ActIngestionService.class.getDeclaredMethod("countTrackedTargetsWithActiveDots");
        countTrackedTargetsWithActiveDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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
        ActIngestionService ingestion = new ActIngestionService(
                capturePort,
                combatService,
                new FflogsZoneLookup(new ObjectMapper())
        );

        Map<Long, Instant> lastSeenByTarget = new HashMap<>();
        Map<String, EvidenceAgeStats> statsByBucket = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof NetworkAbilityRaw ability) {
                lastSeenByTarget.put(ability.targetId(), ability.ts());
            } else if (parsed instanceof BuffApplyRaw buffApply) {
                lastSeenByTarget.put(buffApply.targetId(), buffApply.ts());
            } else if (parsed instanceof BuffRemoveRaw buffRemove) {
                lastSeenByTarget.put(buffRemove.targetId(), buffRemove.ts());
            } else if (parsed instanceof DotTickRaw seenDot) {
                lastSeenByTarget.put(seenDot.targetId(), seenDot.ts());
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
                lastSeenByTarget.put(snapshot.actorId(), snapshot.ts());
            }

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == localActorId) {
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                boolean acceptedBySource = (boolean) shouldAcceptDot.invoke(ingestion, dot);
                Integer recentSourceActionId = (Integer) resolveRecentSourceUnknownStatusActionId.invoke(ingestion, dot);
                Integer recentExactActionId = (Integer) resolveRecentExactUnknownStatusActionId.invoke(ingestion, dot, 15_000L);
                long activeTargets = ((Number) countTrackedTargetsWithActiveDots.invoke(ingestion)).longValue();

                String assignmentKey = dotAttributionAssignmentKey(mode, localActorId, dot.targetId(), guid);
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                Instant lastSeen = lastSeenByTarget.get(dot.targetId());
                String targetSeenAge = lastSeen == null ? "null" : String.valueOf(Duration.between(lastSeen, dot.ts()).toMillis());
                String sampleTracked = trackedTargetDots.stream()
                        .limit(4)
                        .map(SubmissionParityReportDiagnostics::trackedDotStateString)
                        .collect(Collectors.joining(", "));
                String bucket = "target=%s|accepted=%s|activeTargets=%d|trackedCount=%d|sourceTrackedCount=%d|recentExact=%s|recentSource=%s|targetSeenAge=%s".formatted(
                        dot.targetName(),
                        acceptedBySource,
                        activeTargets,
                        trackedTargetDots.size(),
                        trackedSourceDots.size(),
                        formatGuid(recentExactActionId),
                        formatGuid(recentSourceActionId),
                        targetSeenAge
                );
                statsByBucket.compute(
                        bucket,
                        (ignored, existing) -> {
                            EvidenceAgeStats stats = existing == null ? new EvidenceAgeStats() : existing;
                            return stats.add(dot.damage(), sampleTracked);
                        }
                );
                continue;
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf(
                "%s %s targetSeenAgeBuckets fight=%d actor=%s guid=%s%n",
                label,
                mode,
                fightId,
                comparison.localName(),
                formatGuid(guid)
        );
        statsByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> System.out.printf(
                        "  %s hits=%d damage=%d sample=%s%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().damageTotal(),
                        entry.getValue().sample()
                ));
    }

    private static String formatJobId(Integer jobId) {
        if (jobId == null) {
            return "unknownJob";
        }
        return Integer.toHexString(jobId).toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private void printFallbackSplitForeignSourceWindow(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            long foreignSourceId,
            String label
    ) throws Exception {
        printSplitForeignSourceWindow(
                submissionId,
                fightId,
                actorType,
                guid,
                foreignSourceId,
                "status0_fallback_tracked_target_split",
                label
        );
    }

    @SuppressWarnings("unchecked")
    private void printSplitForeignSourceWindow(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            long foreignSourceId,
            String mode,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method resolveTrackedTargetDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedTargetDots", DotTickRaw.class);
        resolveTrackedTargetDots.setAccessible(true);
        Method resolveTrackedSourceDots = ActIngestionService.class.getDeclaredMethod("resolveTrackedSourceDots", DotTickRaw.class);
        resolveTrackedSourceDots.setAccessible(true);

        ActLineParser parser = new ActLineParser();
        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
                    @Override
                    public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                            com.bohouse.pacemeter.core.event.CombatEvent event
                    ) {
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

        List<String> sampleWindows = new ArrayList<>();
        ArrayDeque<String> recentContext = new ArrayDeque<>();
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

            String summary = summarizeParsedLineForFallbackWindow(parsed);
            if (!summary.isBlank()) {
                recentContext.addLast(summary);
                while (recentContext.size() > 10) {
                    recentContext.removeFirst();
                }
            }

            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == foreignSourceId) {
                String assignmentKey = dotAttributionAssignmentKey(mode, localActorId, dot.targetId(), guid);
                long assignedBefore = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                List<Object> trackedTargetDots = (List<Object>) resolveTrackedTargetDots.invoke(ingestion, dot);
                List<Object> trackedSourceDots = (List<Object>) resolveTrackedSourceDots.invoke(ingestion, dot);
                ingestion.onParsed(parsed);
                long assignedAfter = ingestion.debugDotAttributionAssignedAmounts().getOrDefault(assignmentKey, 0L);
                long assignedDelta = assignedAfter - assignedBefore;
                if (assignedDelta <= 0L) {
                    continue;
                }

                String header = "tick ts=%s target=%s(%s) source=%s damage=%d assigned=%d trackedTarget=%s trackedSource=%s".formatted(
                        dot.ts(),
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        dot.targetName(),
                        Long.toHexString(dot.sourceId()).toUpperCase(),
                        dot.damage(),
                        assignedDelta,
                        trackedTargetDots.stream().limit(4).map(SubmissionParityReportDiagnostics::trackedDotStateString).collect(Collectors.joining(", ")),
                        trackedSourceDots.stream().limit(4).map(SubmissionParityReportDiagnostics::trackedDotStateString).collect(Collectors.joining(", "))
                );
                String context = recentContext.stream().collect(Collectors.joining("\n    "));
                sampleWindows.add(header + "\n    " + context);
                if (sampleWindows.size() >= 6) {
                    break;
                }
                continue;
            }

            ingestion.onParsed(parsed);
        }

        System.out.printf(
                "%s %s foreignSourceWindow fight=%d actor=%s guid=%s foreignSource=%s%n",
                label,
                mode,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                Long.toHexString(foreignSourceId).toUpperCase()
        );
        sampleWindows.forEach(window -> System.out.println("  " + window));
    }

    private static String summarizeParsedLineForFallbackWindow(ParsedLine parsed) {
        if (parsed instanceof NetworkAbilityRaw ability) {
            return "21 ts=%s src=%s target=%s skill=%s".formatted(
                    ability.ts(),
                    Long.toHexString(ability.actorId()).toUpperCase(),
                    Long.toHexString(ability.targetId()).toUpperCase(),
                    formatGuid(ability.skillId())
            );
        }
        if (parsed instanceof BuffApplyRaw buffApply) {
            return "26 ts=%s src=%s target=%s status=%s".formatted(
                    buffApply.ts(),
                    Long.toHexString(buffApply.sourceId()).toUpperCase(),
                    Long.toHexString(buffApply.targetId()).toUpperCase(),
                    formatGuid(buffApply.statusId())
            );
        }
        if (parsed instanceof BuffRemoveRaw buffRemove) {
            return "30 ts=%s src=%s target=%s status=%s".formatted(
                    buffRemove.ts(),
                    Long.toHexString(buffRemove.sourceId()).toUpperCase(),
                    Long.toHexString(buffRemove.targetId()).toUpperCase(),
                    formatGuid(buffRemove.statusId())
            );
        }
        if (parsed instanceof DotStatusSignalRaw signal) {
            String signals = signal.signals().stream()
                    .limit(4)
                    .map(statusSignal -> "%s:%s".formatted(
                            Long.toHexString(statusSignal.sourceId()).toUpperCase(),
                            formatGuid(statusSignal.statusId())
                    ))
                    .collect(Collectors.joining(","));
            return "37 ts=%s target=%s signals=%s".formatted(
                    signal.ts(),
                    Long.toHexString(signal.targetId()).toUpperCase(),
                    signals
            );
        }
        if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
            String statuses = snapshot.statuses().stream()
                    .limit(4)
                    .map(status -> "%s:%s".formatted(
                            Long.toHexString(status.sourceId()).toUpperCase(),
                            formatGuid(status.statusId())
                    ))
                    .collect(Collectors.joining(","));
            return "38 ts=%s target=%s statuses=%s".formatted(
                    snapshot.ts(),
                    Long.toHexString(snapshot.actorId()).toUpperCase(),
                    statuses
            );
        }
        if (parsed instanceof DotTickRaw dot) {
            return "24 ts=%s src=%s target=%s status=%s dmg=%d".formatted(
                    dot.ts(),
                    Long.toHexString(dot.sourceId()).toUpperCase(),
                    Long.toHexString(dot.targetId()).toUpperCase(),
                    formatGuid(dot.statusId()),
                    dot.damage()
            );
        }
        return "";
    }

    private static Instant extractParsedLineTimestamp(ParsedLine parsed) {
        if (parsed instanceof NetworkAbilityRaw ability) {
            return ability.ts();
        }
        if (parsed instanceof BuffApplyRaw buffApply) {
            return buffApply.ts();
        }
        if (parsed instanceof BuffRemoveRaw buffRemove) {
            return buffRemove.ts();
        }
        if (parsed instanceof DotStatusSignalRaw signal) {
            return signal.ts();
        }
        if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot) {
            return snapshot.ts();
        }
        if (parsed instanceof DotTickRaw dot) {
            return dot.ts();
        }
        return null;
    }

    private static String summarizeSameActionDualTargetLifecycleLine(
            ParsedLine parsed,
            long localActorId,
            int guid,
            int statusId,
            List<Long> targetIds
    ) {
        if (parsed instanceof NetworkAbilityRaw ability
                && ability.actorId() == localActorId
                && (ability.skillId() == guid || targetIds.contains(ability.targetId()))) {
            return "21 ts=%s target=%s(%s) skill=%s damage=%d".formatted(
                    ability.ts(),
                    Long.toHexString(ability.targetId()).toUpperCase(),
                    ability.targetName(),
                    formatGuid(ability.skillId()),
                    ability.damage()
            );
        }
        if (parsed instanceof BuffApplyRaw buffApply
                && buffApply.sourceId() == localActorId
                && buffApply.statusId() == statusId
                && targetIds.contains(buffApply.targetId())) {
            return "26 ts=%s target=%s(%s) status=%s duration=%.1f".formatted(
                    buffApply.ts(),
                    Long.toHexString(buffApply.targetId()).toUpperCase(),
                    buffApply.targetName(),
                    formatGuid(buffApply.statusId()),
                    buffApply.durationSec()
            );
        }
        if (parsed instanceof BuffRemoveRaw buffRemove
                && buffRemove.sourceId() == localActorId
                && buffRemove.statusId() == statusId
                && targetIds.contains(buffRemove.targetId())) {
            return "30 ts=%s target=%s(%s) status=%s".formatted(
                    buffRemove.ts(),
                    Long.toHexString(buffRemove.targetId()).toUpperCase(),
                    buffRemove.targetName(),
                    formatGuid(buffRemove.statusId())
            );
        }
        if (parsed instanceof DotStatusSignalRaw signal && targetIds.contains(signal.targetId())) {
            String sourceStatuses = signal.signals().stream()
                    .filter(statusSignal -> statusSignal.sourceId() == localActorId)
                    .filter(statusSignal -> statusSignal.statusId() == statusId)
                    .map(statusSignal -> "%s:%s".formatted(
                            Long.toHexString(statusSignal.sourceId()).toUpperCase(),
                            formatGuid(statusSignal.statusId())
                    ))
                    .collect(Collectors.joining(","));
            if (!sourceStatuses.isBlank()) {
                return "37 ts=%s target=%s signals=%s".formatted(
                        signal.ts(),
                        Long.toHexString(signal.targetId()).toUpperCase(),
                        sourceStatuses
                );
            }
        }
        if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot
                && targetIds.contains(snapshot.actorId())) {
            String sourceStatuses = snapshot.statuses().stream()
                    .filter(status -> status.sourceId() == localActorId)
                    .filter(status -> status.statusId() == statusId)
                    .map(status -> "%s:%s".formatted(
                            Long.toHexString(status.sourceId()).toUpperCase(),
                            formatGuid(status.statusId())
                    ))
                    .collect(Collectors.joining(","));
            if (!sourceStatuses.isBlank()) {
                return "38 ts=%s target=%s statuses=%s".formatted(
                        snapshot.ts(),
                        Long.toHexString(snapshot.actorId()).toUpperCase(),
                        sourceStatuses
                );
            }
        }
        if (parsed instanceof DotTickRaw dot
                && dot.sourceId() == localActorId
                && dot.statusId() == 0
                && targetIds.contains(dot.targetId())) {
            return "24 ts=%s target=%s(%s) status=%s damage=%d".formatted(
                    dot.ts(),
                    Long.toHexString(dot.targetId()).toUpperCase(),
                    dot.targetName(),
                    formatGuid(dot.statusId()),
                    dot.damage()
            );
        }
        return null;
    }

    private static String targetNameForId(SubmissionParityReport report, long targetId) {
        return report.combat().actors().stream()
                .filter(actor -> actor.actorId().value() == targetId)
                .map(CombatDebugSnapshot.ActorDebugEntry::name)
                .findFirst()
                .orElse(Long.toHexString(targetId).toUpperCase());
    }

    private void printActorDotLifecycle(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            int statusId,
            Set<Long> targetIds,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        System.out.printf(
                "%s dotLifecycle fight=%d actor=%s guid=%s status=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                formatGuid(statusId)
        );

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
            if (parsed instanceof BuffApplyRaw buffApply
                    && buffApply.sourceId() == localActorId
                    && buffApply.statusId() == statusId
                    && targetIds.contains(buffApply.targetId())) {
                System.out.printf(
                        "  APPLY ts=%s target=%s(%s) duration=%.1f%n",
                        buffApply.ts(),
                        Long.toHexString(buffApply.targetId()).toUpperCase(),
                        buffApply.targetName(),
                        buffApply.durationSec()
                );
                continue;
            }
            if (parsed instanceof BuffRemoveRaw buffRemove
                    && buffRemove.sourceId() == localActorId
                    && buffRemove.statusId() == statusId
                    && targetIds.contains(buffRemove.targetId())) {
                System.out.printf(
                        "  REMOVE ts=%s target=%s(%s)%n",
                        buffRemove.ts(),
                        Long.toHexString(buffRemove.targetId()).toUpperCase(),
                        buffRemove.targetName()
                );
                continue;
            }
            if (parsed instanceof DotTickRaw dot
                    && dot.sourceId() == localActorId
                    && dot.statusId() == 0
                    && targetIds.contains(dot.targetId())) {
                System.out.printf(
                        "  TICK ts=%s target=%s(%s) damage=%d raw=%s%n",
                        dot.ts(),
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        dot.targetName(),
                        dot.damage(),
                        dot.rawLine()
                );
                continue;
            }
            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.actorId() == localActorId
                    && ability.skillId() == guid
                    && targetIds.contains(ability.targetId())) {
                System.out.printf(
                        "  DIRECT ts=%s target=%s(%s) damage=%d raw=%s%n",
                        ability.ts(),
                        Long.toHexString(ability.targetId()).toUpperCase(),
                        ability.targetName(),
                        ability.damage(),
                        ability.rawLine()
                );
            }
        }
    }

    private void printActorDotLifecycleWindow(
            String submissionId,
            int fightId,
            String actorType,
            int guid,
            int statusId,
            Set<Long> targetIds,
            long startMsInclusive,
            long endMsInclusive,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        Instant fightStart = Instant.ofEpochMilli(report.fflogs().reportStartTime() + report.fflogs().fights().stream()
                .filter(f -> f.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow());

        System.out.printf(
                "%s dotLifecycleWindow fight=%d actor=%s guid=%s status=%s window=[%d,%d]%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(guid),
                formatGuid(statusId),
                startMsInclusive,
                endMsInclusive
        );

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

            Instant ts = null;
            if (parsed instanceof BuffApplyRaw buffApply) {
                ts = buffApply.ts();
            } else if (parsed instanceof BuffRemoveRaw buffRemove) {
                ts = buffRemove.ts();
            } else if (parsed instanceof DotTickRaw dotTick) {
                ts = dotTick.ts();
            } else if (parsed instanceof NetworkAbilityRaw ability) {
                ts = ability.ts();
            }
            if (ts == null) {
                continue;
            }
            long tsMs = Duration.between(fightStart, ts).toMillis();
            if (tsMs < startMsInclusive || tsMs > endMsInclusive) {
                continue;
            }

            if (parsed instanceof BuffApplyRaw buffApply
                    && buffApply.sourceId() == localActorId
                    && buffApply.statusId() == statusId
                    && targetIds.contains(buffApply.targetId())) {
                System.out.printf(
                        "  %d APPLY target=%s(%s) duration=%.1f%n",
                        tsMs,
                        Long.toHexString(buffApply.targetId()).toUpperCase(),
                        buffApply.targetName(),
                        buffApply.durationSec()
                );
                continue;
            }
            if (parsed instanceof BuffRemoveRaw buffRemove
                    && buffRemove.sourceId() == localActorId
                    && buffRemove.statusId() == statusId
                    && targetIds.contains(buffRemove.targetId())) {
                System.out.printf(
                        "  %d REMOVE target=%s(%s)%n",
                        tsMs,
                        Long.toHexString(buffRemove.targetId()).toUpperCase(),
                        buffRemove.targetName()
                );
                continue;
            }
            if (parsed instanceof DotTickRaw dot
                    && dot.sourceId() == localActorId
                    && dot.statusId() == 0
                    && targetIds.contains(dot.targetId())) {
                System.out.printf(
                        "  %d TICK target=%s(%s) damage=%d%n",
                        tsMs,
                        Long.toHexString(dot.targetId()).toUpperCase(),
                        dot.targetName(),
                        dot.damage()
                );
                continue;
            }
            if (parsed instanceof NetworkAbilityRaw ability
                    && ability.actorId() == localActorId
                    && ability.skillId() == guid
                    && targetIds.contains(ability.targetId())) {
                System.out.printf(
                        "  %d DIRECT target=%s(%s) damage=%d%n",
                        tsMs,
                        Long.toHexString(ability.targetId()).toUpperCase(),
                        ability.targetName(),
                        ability.damage()
                );
            }
        }
    }

    private void printSnapshotWindow(
            String submissionId,
            int fightId,
            Set<Long> targetIds,
            long startMsInclusive,
            long endMsInclusive,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        ActLineParser parser = new ActLineParser();

        long fightStartOffset = report.fflogs().fights().stream()
                .filter(f -> f.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        Instant fightStart = Instant.ofEpochMilli(report.fflogs().reportStartTime() + fightStartOffset);

        System.out.printf(
                "%s snapshotWindow fight=%d window=[%d,%d]%n",
                label,
                fightId,
                startMsInclusive,
                endMsInclusive
        );

        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }
            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.StatusSnapshotRaw snapshot
                    && targetIds.contains(snapshot.actorId())) {
                long tsMs = Duration.between(fightStart, snapshot.ts()).toMillis();
                if (tsMs >= startMsInclusive && tsMs <= endMsInclusive) {
                    System.out.printf(
                            "  %d SNAPSHOT target=%s(%s) statuses=%s%n",
                            tsMs,
                            Long.toHexString(snapshot.actorId()).toUpperCase(),
                            snapshot.actorName(),
                            snapshot.statuses().stream()
                                    .map(status -> Integer.toHexString(status.statusId()).toUpperCase()
                                            + ":" + Long.toHexString(status.sourceId()).toUpperCase())
                                    .toList()
                    );
                }
            } else if (parsed instanceof com.bohouse.pacemeter.adapter.inbound.actws.CombatantStatusSnapshotRaw snapshot
                    && targetIds.contains(snapshot.actorId())) {
                long tsMs = Duration.between(fightStart, snapshot.ts()).toMillis();
                if (tsMs >= startMsInclusive && tsMs <= endMsInclusive) {
                    System.out.printf(
                            "  %d COMBATANT_SNAPSHOT target=%s(%s) statuses=%s%n",
                            tsMs,
                            Long.toHexString(snapshot.actorId()).toUpperCase(),
                            snapshot.actorName(),
                            snapshot.statuses().stream()
                                    .map(status -> Integer.toHexString(status.statusId()).toUpperCase()
                                            + ":" + Long.toHexString(status.sourceId()).toUpperCase())
                                    .toList()
                    );
                }
            }
        }
    }

    private void printActorFirstFflogsEventsByAbility(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                primaryGuid
        );
        List<FflogsApiClient.DamageEventEntry> fallbackEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                fallbackGuid
        );

        List<FflogsApiClient.DamageEventEntry> events = Stream.concat(primaryEvents.stream(), fallbackEvents.stream())
                .sorted(Comparator.comparingLong(FflogsApiClient.DamageEventEntry::timestamp))
                .toList();

        System.out.printf(
                "%s fflogsFirstEvents fight=%d actor=%s primary=%s fallback=%s totalEvents=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid),
                events.size()
        );
        events.stream()
                .limit(20)
                .forEach(event -> System.out.printf(
                        "  ts=%d target=%s ability=%s amount=%d hitType=%s%n",
                        event.timestamp(),
                        Integer.toHexString(event.targetId()).toUpperCase(),
                        formatGuid(event.abilityGameId()),
                        event.amount(),
                        event.hitType() == null ? "null" : event.hitType().toString()
                ));
    }

    private void printSelectedFightOffset(String submissionId, int fightId, String label) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.FflogsFightSummary fight = report.fflogs().fights().stream()
                .filter(value -> value.id() == fightId)
                .findFirst()
                .orElseThrow();

        System.out.printf(
                "%s selectedFightOffset fight=%d reportStart=%d fightStart=%d fightEnd=%d relativeStart=%d duration=%d%n",
                label,
                fightId,
                report.fflogs().reportStartTime(),
                fight.startTime(),
                fight.endTime(),
                fight.startTime(),
                fight.endTime() - fight.startTime()
        );
    }

    private void printFflogsActors(String submissionId, int fightId, String label) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        System.out.printf("%s fflogsActors fight=%d%n", label, fightId);
        report.fflogs().actors().stream()
                .sorted(Comparator.comparing(SubmissionParityReport.FflogsActorSummary::id))
                .forEach(actor -> System.out.printf(
                        "  id=%s name=%s type=%s total=%.0f rdps=%.1f%n",
                        actor.id() == null ? "null" : Integer.toHexString(actor.id()).toUpperCase(),
                        actor.name(),
                        actor.type(),
                        actor.total(),
                        actor.rdpsPerSecond()
                ));
    }

    private void printActorTargetTimeline(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long fightStartOffset = report.fflogs().fights().stream()
                .filter(fight -> fight.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

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

        Map<Long, String> targetNameById = new HashMap<>();
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
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        Map<Integer, TimelineStats> localByTarget = new HashMap<>();
        capturedDamageEvents.stream()
                .filter(e -> e.sourceId().value() == localActorId)
                .filter(e -> e.actionId() == primaryGuid || e.actionId() == fallbackGuid)
                .forEach(event -> localByTarget.compute(
                        (int) event.targetId().value(),
                        (ignored, existing) -> TimelineStats.add(
                                existing,
                                event.amount(),
                                event.timestampMs()
                        )
                ));

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                primaryGuid
        );
        List<FflogsApiClient.DamageEventEntry> fallbackEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                fallbackGuid
        );

        Map<Integer, TimelineStats> fflogsByTarget = new HashMap<>();
        Stream.concat(primaryEvents.stream(), fallbackEvents.stream())
                .forEach(event -> fflogsByTarget.compute(
                        event.targetId(),
                        (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestamp() - fightStartOffset)
                ));

        System.out.printf(
                "%s targetTimeline fight=%d actor=%s primary=%s fallback=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid)
        );
        System.out.println("  localTargets");
        localByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().totalDamage(), left.getValue().totalDamage()))
                .forEach(entry -> System.out.printf(
                        "    id=%s name=%s total=%d hits=%d first=%d last=%d%n",
                        Integer.toHexString(entry.getKey()).toUpperCase(),
                        targetNameById.getOrDefault(entry.getKey().longValue(), "?"),
                        entry.getValue().totalDamage(),
                        entry.getValue().hitCount(),
                        entry.getValue().firstTimestamp(),
                        entry.getValue().lastTimestamp()
                ));
        System.out.println("  fflogsTargets");
        fflogsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().totalDamage(), left.getValue().totalDamage()))
                .forEach(entry -> System.out.printf(
                        "    id=%s total=%d hits=%d first=%d last=%d%n",
                        Integer.toHexString(entry.getKey()).toUpperCase(),
                        entry.getValue().totalDamage(),
                        entry.getValue().hitCount(),
                        entry.getValue().firstTimestamp(),
                        entry.getValue().lastTimestamp()
                ));
    }

    private void printActorWindowedLocalTotals(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long fightStartOffset = report.fflogs().fights().stream()
                .filter(fight -> fight.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

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

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                primaryGuid
        );
        List<FflogsApiClient.DamageEventEntry> fallbackEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                fallbackGuid
        );

        Map<Integer, TimelineStats> fflogsByTarget = new HashMap<>();
        Stream.concat(primaryEvents.stream(), fallbackEvents.stream())
                .forEach(event -> fflogsByTarget.compute(
                        event.targetId(),
                        (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestamp() - fightStartOffset)
                ));

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> localEvents = capturedDamageEvents.stream()
                .filter(e -> e.sourceId().value() == localActorId)
                .filter(e -> e.actionId() == primaryGuid || e.actionId() == fallbackGuid)
                .toList();

        System.out.printf(
                "%s windowedLocalTotals fight=%d actor=%s primary=%s fallback=%s%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid)
        );
        fflogsByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().totalDamage(), left.getValue().totalDamage()))
                .forEach(entry -> {
                    TimelineStats stats = entry.getValue();
                    long localWindowTotal = localEvents.stream()
                            .filter(event -> event.timestampMs() >= stats.firstTimestamp())
                            .filter(event -> event.timestampMs() <= stats.lastTimestamp())
                            .mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount)
                            .sum();
                    long localWindowHits = localEvents.stream()
                            .filter(event -> event.timestampMs() >= stats.firstTimestamp())
                            .filter(event -> event.timestampMs() <= stats.lastTimestamp())
                            .count();
                    System.out.printf(
                            "  fflogsTarget=%s fflogsTotal=%d fflogsHits=%d window=[%d,%d] localWindowTotal=%d localWindowHits=%d%n",
                            Integer.toHexString(entry.getKey()).toUpperCase(),
                            stats.totalDamage(),
                            stats.hitCount(),
                            stats.firstTimestamp(),
                            stats.lastTimestamp(),
                            localWindowTotal,
                            localWindowHits
                    );
                });
    }

    private void printActorHitLeakAgainstFflogsWindows(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long fightStartOffset = report.fflogs().fights().stream()
                .filter(fight -> fight.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

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

        Map<Long, String> targetNameById = new HashMap<>();
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
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.DamageEventEntry> primaryEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                primaryGuid
        );
        List<FflogsApiClient.DamageEventEntry> fallbackEvents = apiClient.fetchDamageDoneEventsByAbility(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId(),
                fallbackGuid
        );

        List<TimelineWindow> fflogsWindows = Stream.concat(primaryEvents.stream(), fallbackEvents.stream())
                .collect(Collectors.groupingBy(FflogsApiClient.DamageEventEntry::targetId))
                .entrySet().stream()
                .map(entry -> {
                    long first = entry.getValue().stream()
                            .mapToLong(event -> event.timestamp() - fightStartOffset)
                            .min()
                            .orElse(0L);
                    long last = entry.getValue().stream()
                            .mapToLong(event -> event.timestamp() - fightStartOffset)
                            .max()
                            .orElse(0L);
                    return new TimelineWindow(entry.getKey(), first, last);
                })
                .sorted(Comparator.comparingLong(TimelineWindow::startMs))
                .toList();

        List<com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent> localEvents = capturedDamageEvents.stream()
                .filter(e -> e.sourceId().value() == localActorId)
                .filter(e -> e.actionId() == primaryGuid || e.actionId() == fallbackGuid)
                .sorted(Comparator.comparingLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::timestampMs))
                .toList();

        long localInsideWindowHits = 0L;
        long localInsideWindowDamage = 0L;
        Map<Long, TimelineStats> outsideByTarget = new HashMap<>();
        Map<String, TimelineStats> outsideByBucket = new HashMap<>();
        List<String> sampleOutsideEvents = new ArrayList<>();
        for (com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent event : localEvents) {
            TimelineWindow matchedWindow = fflogsWindows.stream()
                    .filter(window -> event.timestampMs() >= window.startMs() && event.timestampMs() <= window.endMs())
                    .findFirst()
                    .orElse(null);
            if (matchedWindow != null) {
                localInsideWindowHits++;
                localInsideWindowDamage += event.amount();
                continue;
            }

            outsideByTarget.compute(
                    event.targetId().value(),
                    (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestampMs())
            );
            String bucket = classifyTimelinePosition(event.timestampMs(), fflogsWindows);
            outsideByBucket.compute(
                    bucket,
                    (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestampMs())
            );
            if (sampleOutsideEvents.size() < 12) {
                sampleOutsideEvents.add(
                        "ts=%d target=%s(%s) amount=%d".formatted(
                                event.timestampMs(),
                                Long.toHexString(event.targetId().value()).toUpperCase(),
                                targetNameById.getOrDefault(event.targetId().value(), "?"),
                                event.amount()
                        )
                );
            }
        }

        System.out.printf(
                "%s hitLeakVsFflogsWindows fight=%d actor=%s primary=%s fallback=%s totalLocalHits=%d totalLocalDamage=%d insideWindowHits=%d insideWindowDamage=%d outsideWindowHits=%d outsideWindowDamage=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid),
                localEvents.size(),
                localEvents.stream().mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount).sum(),
                localInsideWindowHits,
                localInsideWindowDamage,
                localEvents.size() - localInsideWindowHits,
                localEvents.stream().mapToLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::amount).sum() - localInsideWindowDamage
        );
        System.out.println("  outsideByBucket");
        outsideByBucket.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().hitCount(), left.getValue().hitCount()))
                .forEach(entry -> System.out.printf(
                        "    bucket=%s hits=%d damage=%d first=%d last=%d%n",
                        entry.getKey(),
                        entry.getValue().hitCount(),
                        entry.getValue().totalDamage(),
                        entry.getValue().firstTimestamp(),
                        entry.getValue().lastTimestamp()
                ));
        System.out.println("  outsideByTarget");
        outsideByTarget.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().hitCount(), left.getValue().hitCount()))
                .forEach(entry -> System.out.printf(
                        "    id=%s name=%s hits=%d damage=%d first=%d last=%d%n",
                        Long.toHexString(entry.getKey()).toUpperCase(),
                        targetNameById.getOrDefault(entry.getKey(), "?"),
                        entry.getValue().hitCount(),
                        entry.getValue().totalDamage(),
                        entry.getValue().firstTimestamp(),
                        entry.getValue().lastTimestamp()
                ));
        System.out.println("  sampleOutsideEvents");
        sampleOutsideEvents.forEach(sample -> System.out.println("    " + sample));
    }

    private void printActorAlignedEventDiff(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long fightStartOffset = report.fflogs().fights().stream()
                .filter(fight -> fight.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

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

        Map<Long, String> targetNameById = new HashMap<>();
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
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        List<AlignedLocalEvent> localEvents = capturedDamageEvents.stream()
                .filter(event -> event.sourceId().value() == localActorId)
                .filter(event -> event.actionId() == primaryGuid || event.actionId() == fallbackGuid)
                .sorted(Comparator.comparingLong(com.bohouse.pacemeter.core.event.CombatEvent.DamageEvent::timestampMs))
                .map(event -> new AlignedLocalEvent(
                        event.timestampMs(),
                        event.targetId().value(),
                        targetNameById.getOrDefault(event.targetId().value(), "?"),
                        event.amount(),
                        event.damageType(),
                        event.actionId()
                ))
                .toList();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<AlignedFflogsEvent> fflogsEvents = Stream.concat(
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                primaryGuid
                        ).stream(),
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                fallbackGuid
                        ).stream()
                )
                .sorted(Comparator.comparingLong(FflogsApiClient.DamageEventEntry::timestamp))
                .map(event -> new AlignedFflogsEvent(
                        event.timestamp() - fightStartOffset,
                        event.targetId(),
                        event.amount(),
                        event.hitType(),
                        event.abilityGameId()
                ))
                .toList();

        Map<Long, TimelineStats> localTargetStats = new HashMap<>();
        for (AlignedLocalEvent event : localEvents) {
            localTargetStats.compute(
                    event.targetId(),
                    (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestampMs())
            );
        }
        Map<Integer, TimelineStats> fflogsTargetStats = new HashMap<>();
        for (AlignedFflogsEvent event : fflogsEvents) {
            fflogsTargetStats.compute(
                    event.targetId(),
                    (ignored, existing) -> TimelineStats.add(existing, event.amount(), event.timestampMs())
            );
        }

        System.out.printf(
                "%s alignedEventDiff fight=%d actor=%s primary=%s fallback=%s localHits=%d fflogsHits=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid),
                localEvents.size(),
                fflogsEvents.size()
        );

        List<TargetAlignment> alignments = fflogsTargetStats.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().firstTimestamp()))
                .map(entry -> {
                    Map.Entry<Long, TimelineStats> matchedLocal = localTargetStats.entrySet().stream()
                            .min(Comparator.comparingLong(localEntry ->
                                    Math.abs(localEntry.getValue().firstTimestamp() - entry.getValue().firstTimestamp())
                                            + Math.abs(localEntry.getValue().lastTimestamp() - entry.getValue().lastTimestamp())))
                            .orElse(null);
                    return new TargetAlignment(
                            entry.getKey(),
                            entry.getValue(),
                            matchedLocal == null ? null : matchedLocal.getKey(),
                            matchedLocal == null ? null : matchedLocal.getValue()
                    );
                })
                .toList();

        for (TargetAlignment alignment : alignments) {
            List<AlignedLocalEvent> localTargetEvents = alignment.localTargetId() == null
                    ? List.of()
                    : localEvents.stream()
                    .filter(event -> event.targetId() == alignment.localTargetId())
                    .toList();
            List<AlignedFflogsEvent> fflogsTargetEvents = fflogsEvents.stream()
                    .filter(event -> event.targetId() == alignment.fflogsTargetId())
                    .toList();
            long localDirectHits = localTargetEvents.stream()
                    .filter(event -> event.damageType() == DamageType.DIRECT)
                    .count();
            long localDirectDamage = localTargetEvents.stream()
                    .filter(event -> event.damageType() == DamageType.DIRECT)
                    .mapToLong(AlignedLocalEvent::amount)
                    .sum();
            long localDotHits = localTargetEvents.stream()
                    .filter(event -> event.damageType() == DamageType.DOT)
                    .count();
            long localDotDamage = localTargetEvents.stream()
                    .filter(event -> event.damageType() == DamageType.DOT)
                    .mapToLong(AlignedLocalEvent::amount)
                    .sum();

            System.out.printf(
                    "  pair fflogs=%s[%d hits/%d dmg %d..%d] local=%s(%s)[%d hits/%d dmg %d..%d] direct=%d/%d dot=%d/%d%n",
                    Integer.toHexString(alignment.fflogsTargetId()).toUpperCase(),
                    alignment.fflogsStats().hitCount(),
                    alignment.fflogsStats().totalDamage(),
                    alignment.fflogsStats().firstTimestamp(),
                    alignment.fflogsStats().lastTimestamp(),
                    alignment.localTargetId() == null ? "null" : Long.toHexString(alignment.localTargetId()).toUpperCase(),
                    alignment.localTargetId() == null ? "?" : targetNameById.getOrDefault(alignment.localTargetId(), "?"),
                    alignment.localStats() == null ? 0L : alignment.localStats().hitCount(),
                    alignment.localStats() == null ? 0L : alignment.localStats().totalDamage(),
                    alignment.localStats() == null ? 0L : alignment.localStats().firstTimestamp(),
                    alignment.localStats() == null ? 0L : alignment.localStats().lastTimestamp(),
                    localDirectHits,
                    localDirectDamage,
                    localDotHits,
                    localDotDamage
            );
            System.out.println("    localFirst");
            localTargetEvents.stream()
                    .limit(12)
                    .forEach(event -> System.out.printf(
                            "      ts=%d kind=%s amount=%d action=%s%n",
                            event.timestampMs(),
                            event.damageType(),
                            event.amount(),
                            formatGuid(event.actionId())
                    ));
            System.out.println("    fflogsFirst");
            fflogsTargetEvents.stream()
                    .limit(12)
                    .forEach(event -> System.out.printf(
                            "      ts=%d amount=%d hitType=%s ability=%s%n",
                            event.timestampMs(),
                            event.amount(),
                            event.hitType() == null ? "null" : event.hitType().toString(),
                            formatGuid(event.abilityGameId())
                    ));
        }
    }

    private void printActorAlignedEventWindow(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            long centerMs,
            long radiusMs,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();
        long fightStartOffset = report.fflogs().fights().stream()
                .filter(fight -> fight.id() == fightId)
                .mapToLong(SubmissionParityReport.FflogsFightSummary::startTime)
                .findFirst()
                .orElseThrow();
        long localActorId = report.combat().actors().stream()
                .filter(actor -> comparison.localName().equals(actor.name()))
                .mapToLong(actor -> actor.actorId().value())
                .findFirst()
                .orElseThrow();

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

        Map<Long, String> targetNameById = new HashMap<>();
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
            if (parsed instanceof NetworkAbilityRaw ability) {
                targetNameById.putIfAbsent(ability.targetId(), ability.targetName());
            } else if (parsed instanceof DotTickRaw dot) {
                targetNameById.putIfAbsent(dot.targetId(), dot.targetName());
            }
            ingestion.onParsed(parsed);
        }

        List<AlignedLocalEvent> localEvents = capturedDamageEvents.stream()
                .filter(event -> event.sourceId().value() == localActorId)
                .filter(event -> event.actionId() == primaryGuid || event.actionId() == fallbackGuid)
                .map(event -> new AlignedLocalEvent(
                        event.timestampMs(),
                        event.targetId().value(),
                        targetNameById.getOrDefault(event.targetId().value(), "?"),
                        event.amount(),
                        event.damageType(),
                        event.actionId()
                ))
                .filter(event -> Math.abs(event.timestampMs() - centerMs) <= radiusMs)
                .sorted(Comparator.comparingLong(AlignedLocalEvent::timestampMs))
                .toList();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<AlignedFflogsEvent> fflogsEvents = Stream.concat(
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                primaryGuid
                        ).stream(),
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                fallbackGuid
                        ).stream()
                )
                .map(event -> new AlignedFflogsEvent(
                        event.timestamp() - fightStartOffset,
                        event.targetId(),
                        event.amount(),
                        event.hitType(),
                        event.abilityGameId()
                ))
                .filter(event -> Math.abs(event.timestampMs() - centerMs) <= radiusMs)
                .sorted(Comparator.comparingLong(AlignedFflogsEvent::timestampMs))
                .toList();

        System.out.printf(
                "%s alignedEventWindow fight=%d actor=%s primary=%s fallback=%s center=%d radius=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid),
                centerMs,
                radiusMs
        );
        System.out.println("  localWindow");
        localEvents.forEach(event -> System.out.printf(
                "    ts=%d target=%s(%s) kind=%s amount=%d action=%s%n",
                event.timestampMs(),
                Long.toHexString(event.targetId()).toUpperCase(),
                event.targetName(),
                event.damageType(),
                event.amount(),
                formatGuid(event.actionId())
        ));
        System.out.println("  fflogsWindow");
        fflogsEvents.forEach(event -> System.out.printf(
                "    ts=%d target=%s amount=%d hitType=%s ability=%s%n",
                event.timestampMs(),
                Integer.toHexString(event.targetId()).toUpperCase(),
                event.amount(),
                event.hitType() == null ? "null" : event.hitType().toString(),
                formatGuid(event.abilityGameId())
        ));
    }

    private void printActorFflogsAbilityBuckets(
            String submissionId,
            int fightId,
            String actorType,
            Set<Integer> exactGuids,
            List<String> nameTokens,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        List<FflogsApiClient.AbilityDamageEntry> abilities = apiClient.fetchDamageDoneAbilities(
                report.fflogs().reportCode(),
                report.fflogs().selectedFightId(),
                comparison.fflogsActorId()
        );

        System.out.printf(
                "%s fflogsAbilityBuckets fight=%d actor=%s%n",
                label,
                fightId,
                comparison.localName()
        );
        abilities.stream()
                .filter(entry -> {
                    String lowerName = entry.name() == null ? "" : entry.name().toLowerCase(Locale.ROOT);
                    return (entry.guid() != null && exactGuids.contains(entry.guid()))
                            || nameTokens.stream().anyMatch(lowerName::contains);
                })
                .sorted((left, right) -> Double.compare(right.total(), left.total()))
                .forEach(entry -> System.out.printf(
                        "  ability name=%s guid=%s total=%.0f type=%s%n",
                        entry.name(),
                        entry.guid() == null ? "null" : formatGuid(entry.guid()),
                        entry.total(),
                        entry.type()
                ));
        System.out.println("  comparisonLocalTopSkills=" + comparison.localTopSkills());
        System.out.println("  comparisonFflogsTopSkills=" + comparison.fflogsTopSkills());
    }

    private void printActorFflogsAbilityVsEvents(
            String submissionId,
            int fightId,
            String actorType,
            int primaryGuid,
            int fallbackGuid,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        SubmissionParityReport.ActorParityComparison comparison = report.comparisons().stream()
                .filter(c -> actorType.equals(c.fflogsType()) || actorType.equals(c.localName()))
                .findFirst()
                .orElseThrow();

        FflogsApiClient apiClient = buildConfiguredApiClient();
        long abilityTotal = Math.round(apiClient.fetchDamageDoneAbilities(
                        report.fflogs().reportCode(),
                        report.fflogs().selectedFightId(),
                        comparison.fflogsActorId()
                ).stream()
                .filter(entry -> entry.guid() != null && (entry.guid() == primaryGuid || entry.guid() == fallbackGuid))
                .mapToDouble(FflogsApiClient.AbilityDamageEntry::total)
                .sum());

        List<FflogsApiClient.DamageEventEntry> events = Stream.concat(
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                primaryGuid
                        ).stream(),
                        apiClient.fetchDamageDoneEventsByAbility(
                                report.fflogs().reportCode(),
                                report.fflogs().selectedFightId(),
                                comparison.fflogsActorId(),
                                fallbackGuid
                        ).stream()
                )
                .sorted(Comparator.comparingLong(FflogsApiClient.DamageEventEntry::timestamp))
                .toList();

        long eventTotal = events.stream().mapToLong(FflogsApiClient.DamageEventEntry::amount).sum();
        System.out.printf(
                "%s fflogsAbilityVsEvents fight=%d actor=%s primary=%s fallback=%s abilityTotal=%d eventTotal=%d delta=%d hits=%d%n",
                label,
                fightId,
                comparison.localName(),
                formatGuid(primaryGuid),
                formatGuid(fallbackGuid),
                abilityTotal,
                eventTotal,
                eventTotal - abilityTotal,
                events.size()
        );
        events.stream()
                .limit(12)
                .forEach(event -> System.out.printf(
                        "  event ts=%d target=%s amount=%d hitType=%s ability=%s%n",
                        event.timestamp(),
                        Integer.toHexString(event.targetId()).toUpperCase(),
                        event.amount(),
                        event.hitType() == null ? "null" : event.hitType().toString(),
                        formatGuid(event.abilityGameId())
                ));
    }

    private String classifyTimelinePosition(long tsMs, List<TimelineWindow> windows) {
        if (windows.isEmpty()) {
            return "no_fflogs_windows";
        }
        if (tsMs < windows.get(0).startMs()) {
            return "before_first_fflogs_window";
        }
        if (tsMs > windows.get(windows.size() - 1).endMs()) {
            return "after_last_fflogs_window";
        }
        return "between_fflogs_windows";
    }

    @SuppressWarnings("unchecked")
    private void printUnknownActiveSubsetStructure(
            String submissionId,
            int fightId,
            String label
    ) throws Exception {
        SubmissionParityReportService service = buildConfiguredHeavy4Service();
        SubmissionParityReport report = service.buildReportForFight(submissionId, fightId);
        assertEquals("ok", report.fflogs().status());
        assertEquals(fightId, report.fflogs().selectedFightId());

        Optional<?> replayWindow = deriveReplayWindow(service, report.fflogs());
        Method shouldIncludeLine = openShouldIncludeLine();
        Method pruneExpiredTrackedDots = ActIngestionService.class.getDeclaredMethod("pruneExpiredTrackedDots", Instant.class);
        pruneExpiredTrackedDots.setAccessible(true);
        ActLineParser parser = new ActLineParser();

        com.bohouse.pacemeter.application.port.inbound.CombatEventPort capturePort =
                new com.bohouse.pacemeter.application.port.inbound.CombatEventPort() {
            @Override
            public com.bohouse.pacemeter.core.engine.EngineResult onEvent(
                    com.bohouse.pacemeter.core.event.CombatEvent event
            ) {
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

        Set<Long> partyActorIds = report.combat().actors().stream()
                .map(actor -> actor.actorId().value())
                .collect(Collectors.toSet());
        Field latestSnapshotsField = ActIngestionService.class.getDeclaredField("latestStatusSnapshotsByTarget");
        latestSnapshotsField.setAccessible(true);
        Field activeTargetDotsField = ActIngestionService.class.getDeclaredField("activeTargetDots");
        activeTargetDotsField.setAccessible(true);
        Field actorNameByIdField = ActIngestionService.class.getDeclaredField("actorNameById");
        actorNameByIdField.setAccessible(true);

        Map<String, StructureStats> statsByKey = new HashMap<>();
        Path combatLog = Path.of("data", "submissions", submissionId, "combat.log");
        for (String line : Files.readAllLines(combatLog, StandardCharsets.UTF_8)) {
            boolean included = (boolean) shouldIncludeLine.invoke(service, line, replayWindow);
            if (!included) {
                continue;
            }

            ParsedLine parsed = parser.parse(line);
            if (parsed instanceof DotTickRaw dot
                    && dot.statusId() == 0
                    && dot.damage() > 0
                    && dot.sourceId() == 0xE0000000L) {
                pruneExpiredTrackedDots.invoke(ingestion, dot.ts());

                Map<Long, Object> latestSnapshotsByTarget =
                        (Map<Long, Object>) latestSnapshotsField.get(ingestion);
                Map<Long, Map<Object, Object>> activeTargetDots =
                        (Map<Long, Map<Object, Object>>) activeTargetDotsField.get(ingestion);
                Map<Long, String> actorNameById = (Map<Long, String>) actorNameByIdField.get(ingestion);
                Object snapshotState = latestSnapshotsByTarget.get(dot.targetId());
                if (snapshotState != null) {
                    Instant snapshotTs = (Instant) invokeAccessor(snapshotState, "ts");
                    long snapshotAgeMs = Math.abs(Duration.between(snapshotTs, dot.ts()).toMillis());
                    if (snapshotAgeMs <= 10_000L) {
                        Map<Object, Double> snapshotWeights =
                                (Map<Object, Double>) invokeAccessor(snapshotState, "weights");
                        Map<Object, Double> fallbackWeights = new HashMap<>();
                        for (Map.Entry<Object, Double> entry : snapshotWeights.entrySet()) {
                            if (partyActorIds.contains(trackedKeySourceId(entry.getKey()))) {
                                fallbackWeights.put(entry.getKey(), entry.getValue());
                            }
                        }
                        if (!fallbackWeights.isEmpty()) {
                            Map<Object, Object> activeDots =
                                    activeTargetDots.getOrDefault(dot.targetId(), Map.of());
                            Map<Object, Double> activeWeights = new HashMap<>();
                            for (Map.Entry<Object, Double> entry : fallbackWeights.entrySet()) {
                                if (activeDots.containsKey(entry.getKey())) {
                                    activeWeights.put(entry.getKey(), entry.getValue());
                                }
                            }
                            double fallbackTotal = fallbackWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeTotal = activeWeights.values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .sum();
                            double activeCoverage = fallbackTotal <= 0.0 ? 0.0 : activeTotal / fallbackTotal;
                            boolean activeSubset = !activeWeights.isEmpty()
                                    && (activeWeights.size() == fallbackWeights.size()
                                    || (activeWeights.size() >= 2 && activeCoverage >= 0.50));
                            if (activeSubset) {
                                long concurrentlyTrackedTargets = activeTargetDots.entrySet().stream()
                                        .filter(entry -> !entry.getValue().isEmpty())
                                        .count();
                                String otherTargets = activeTargetDots.entrySet().stream()
                                        .filter(entry -> !entry.getValue().isEmpty())
                                        .filter(entry -> entry.getKey() != dot.targetId())
                                        .limit(4)
                                        .map(entry -> Long.toHexString(entry.getKey()).toUpperCase()
                                                + "(" + actorNameById.getOrDefault(entry.getKey(), "?") + ")")
                                        .collect(Collectors.joining(", "));
                                String key = "target=%s(%s)|trackedTargets=%d".formatted(
                                        Long.toHexString(dot.targetId()).toUpperCase(),
                                        dot.targetName(),
                                        concurrentlyTrackedTargets
                                );
                                statsByKey.compute(
                                        key,
                                        (ignored, existing) -> {
                                            StructureStats stats = existing == null ? new StructureStats() : existing;
                                            return stats.add(dot.damage(), otherTargets);
                                        }
                                );
                            }
                        }
                    }
                }
            }

            if (parsed != null) {
                ingestion.onParsed(parsed);
            }
        }

        System.out.printf("%s unknownActiveSubsetStructure fight=%d%n", label, fightId);
        statsByKey.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().damageTotal(), left.getValue().damageTotal()))
                .limit(12)
                .forEach(entry -> {
                    StructureStats stats = entry.getValue();
                    System.out.printf(
                            "  %s hits=%d damage=%d sampleOtherTargets=%s%n",
                            entry.getKey(),
                            stats.hitCount(),
                            stats.damageTotal(),
                            stats.sampleOtherTargets()
                    );
                });
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
                .forEach(comparison -> {
                    System.out.printf(
                            "%s fight=%d actor=%s job=%s local=%.1f fflogs=%.1f delta=%.1f ratio=%.4f%n",
                            label,
                            fightId,
                            comparison.localName(),
                            comparison.fflogsType(),
                            comparison.localOnlineRdps(),
                            comparison.fflogsRdpsPerSecond(),
                            comparison.rdpsDelta(),
                            comparison.rdpsDeltaRatio()
                    );
                    System.out.println("  localTopSkills=" + comparison.localTopSkills());
                    System.out.println("  fflogsTopSkills=" + comparison.fflogsTopSkills());
                });
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invokeAccessor(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static long trackedKeySourceId(Object trackedDotKey) throws Exception {
        return ((Number) invokeAccessor(trackedDotKey, "sourceId")).longValue();
    }

    private static int trackedKeyActionId(Object trackedDotKey) throws Exception {
        return ((Number) invokeAccessor(trackedDotKey, "actionId")).intValue();
    }

    private static String trackedKeyString(Object trackedDotKey) {
        try {
            return "%s:%s".formatted(
                    Long.toHexString(trackedKeySourceId(trackedDotKey)).toUpperCase(),
                    formatGuid(trackedKeyActionId(trackedDotKey))
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long trackedDotStateSourceId(Object trackedDotState) throws Exception {
        return ((Number) invokeAccessor(trackedDotState, "sourceId")).longValue();
    }

    private static int trackedDotStateActionId(Object trackedDotState) throws Exception {
        return ((Number) invokeAccessor(trackedDotState, "actionId")).intValue();
    }

    private static String trackedDotStateString(Object trackedDotState) {
        try {
            return "%s:%s".formatted(
                    Long.toHexString(trackedDotStateSourceId(trackedDotState)).toUpperCase(),
                    formatGuid(trackedDotStateActionId(trackedDotState))
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String dotAttributionAssignmentKey(String mode, long sourceId, long targetId, int actionId) {
        return mode
                + "|source=" + Long.toHexString(sourceId).toUpperCase()
                + "|target=" + Long.toHexString(targetId).toUpperCase()
                + "|action=" + Integer.toHexString(actionId).toUpperCase();
    }

    private static long sumModeTargetDelta(
            Map<String, Long> before,
            Map<String, Long> after,
            String mode,
            long targetId
    ) {
        String prefix = mode + "|";
        String targetToken = "|target=" + Long.toHexString(targetId).toUpperCase() + "|";
        long delta = 0L;
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || !key.contains(targetToken)) {
                continue;
            }
            long beforeValue = before.getOrDefault(key, 0L);
            delta += entry.getValue() - beforeValue;
        }
        return delta;
    }

    private static Long dotApplicationAgeMs(Object dotApplication, Instant now) {
        if (dotApplication == null) {
            return null;
        }
        try {
            Method appliedAt = dotApplication.getClass().getDeclaredMethod("appliedAt");
            appliedAt.setAccessible(true);
            Instant ts = (Instant) appliedAt.invoke(dotApplication);
            return Duration.between(ts, now).toMillis();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Long sourceEvidenceTargetId(Object sourceEvidence) {
        if (sourceEvidence == null) {
            return null;
        }
        try {
            Method targetId = sourceEvidence.getClass().getDeclaredMethod("targetId");
            targetId.setAccessible(true);
            return (Long) targetId.invoke(sourceEvidence);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String ageBucket(Long ageMs) {
        if (ageMs == null) {
            return "null";
        }
        if (ageMs < 1_000L) {
            return "<1s";
        }
        if (ageMs < 3_000L) {
            return "1-3s";
        }
        if (ageMs < 8_000L) {
            return "3-8s";
        }
        return "8s+";
    }

    private static String targetMatchBucket(long currentTargetId, Long evidenceTargetId) {
        if (evidenceTargetId == null) {
            return "null";
        }
        return evidenceTargetId == currentTargetId ? "same" : "other";
    }

    private static String formatTargetId(Long targetId) {
        return targetId == null ? "null" : Long.toHexString(targetId).toUpperCase();
    }

    private static String classifyStatus0Source(long sourceId, Set<Long> partyActorIds) {
        if (sourceId == 0xE0000000L) {
            return "unknown";
        }
        if (sourceId == 0L) {
            return "zero";
        }
        if (partyActorIds.contains(sourceId)) {
            return "party";
        }
        if ((sourceId & 0xF0000000L) == 0x40000000L) {
            return "enemy_like";
        }
        if ((sourceId & 0xF0000000L) == 0x10000000L) {
            return "non_party_player_like";
        }
        return "other_non_party";
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
            if (parts.length < 4 || !parts[0].startsWith("status0_")) {
                continue;
            }
            String sourceHex = parts[1].replace("source=", "");
            String actionHex = parts[3].replace("action=", "");
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

    private static String createHeavy2AprilDiagnosticSubmission() throws IOException {
        String submissionId = "2026-04-01-heavy2-f3-KA37dCnZcmHWRfF8";
        Path submissionDir = Path.of("data", "submissions", submissionId);
        Files.createDirectories(submissionDir);
        Files.copy(
                Path.of("src", "main", "resources", "Split-Network_30105_20260401.2026.04.02-2026-04-01T144541.360Z.log"),
                submissionDir.resolve("combat.log"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
        Files.writeString(
                submissionDir.resolve("metadata.json"),
                """
                {
                  "submissionId": "2026-04-01-heavy2-f3-KA37dCnZcmHWRfF8",
                  "submittedAt": "2026-04-01T23:45:41+09:00",
                  "region": "KR",
                  "clientLanguage": "ko",
                  "zoneId": 1323,
                  "encounterName": "아르카디아 선수권: 헤비급(영웅) (2)",
                  "difficulty": "savage",
                  "partyJobs": [],
                  "fflogsReportUrl": "https://ko.fflogs.com/reports/KA37dCnZcmHWRfF8?fight=3&type=damage-done",
                  "fflogsFightId": 3,
                  "pullStartApprox": "full split log start",
                  "hasDotTicks": true,
                  "notes": "Temporary diagnostic submission generated from src/main/resources/Split-Network_30105_20260401.2026.04.02-2026-04-01T144541.360Z.log."
                }
                """,
                StandardCharsets.UTF_8
        );
        return submissionId;
    }

    private static void deleteDiagnosticSubmission(String submissionId) throws IOException {
        Path submissionDir = Path.of("data", "submissions", submissionId);
        if (!Files.exists(submissionDir)) {
            return;
        }
        try (var paths = Files.walk(submissionDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
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

    private record SnapshotSelectionStats(
            long hitCount,
            long damageTotal,
            double activeCoverageTotal,
            int maxFallbackKeyCount,
            int maxActiveKeyCount,
            int maxSameSourceKeyCount,
            List<String> topFallbackKeys
    ) {
        private SnapshotSelectionStats() {
            this(0L, 0L, 0.0, 0, 0, 0, List.of());
        }

        private SnapshotSelectionStats add(
                long damage,
                double activeCoverage,
                Map<Object, Double> fallbackWeights,
                Map<Object, Double> activeWeights,
                Map<Object, Double> sameSourceWeights
        ) {
            List<String> fallbackTop = topFallbackKeys;
            if (fallbackTop.isEmpty()) {
                fallbackTop = fallbackWeights.entrySet().stream()
                        .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                        .limit(4)
                        .map(entry -> trackedKeyString(entry.getKey()) + "=" + String.format("%.3f", entry.getValue()))
                        .toList();
            }
            return new SnapshotSelectionStats(
                    hitCount + 1,
                    damageTotal + damage,
                    activeCoverageTotal + activeCoverage,
                    Math.max(maxFallbackKeyCount, fallbackWeights.size()),
                    Math.max(maxActiveKeyCount, activeWeights.size()),
                    Math.max(maxSameSourceKeyCount, sameSourceWeights.size()),
                    fallbackTop
            );
        }

        private double averageActiveCoverage() {
            if (hitCount == 0L) {
                return 0.0;
            }
            return activeCoverageTotal / hitCount;
        }
    }

    private record TrackedTargetSplitEvidenceStats(
            long hitCount,
            long rawDamageTotal,
            long assignedTotal
    ) {
        private TrackedTargetSplitEvidenceStats() {
            this(0L, 0L, 0L);
        }

        private TrackedTargetSplitEvidenceStats add(long rawDamage, long assigned) {
            return new TrackedTargetSplitEvidenceStats(
                    hitCount + 1L,
                    rawDamageTotal + rawDamage,
                    assignedTotal + assigned
            );
        }
    }

    private record SameSourceSplitMixStats(
            long hitCount,
            long rawDamageTotal,
            long totalSplitAssigned,
            long sameSourceAssignedTotal,
            long foreignAssignedTotal
    ) {
        private SameSourceSplitMixStats() {
            this(0L, 0L, 0L, 0L, 0L);
        }

        private SameSourceSplitMixStats add(
                long rawDamage,
                long splitAssigned,
                long sameSourceAssigned,
                long foreignAssigned
        ) {
            return new SameSourceSplitMixStats(
                    hitCount + 1L,
                    rawDamageTotal + rawDamage,
                    totalSplitAssigned + splitAssigned,
                    sameSourceAssignedTotal + sameSourceAssigned,
                    foreignAssignedTotal + foreignAssigned
            );
        }
    }

    private record StructureStats(
            long hitCount,
            long damageTotal,
            String sampleOtherTargets
    ) {
        private StructureStats() {
            this(0L, 0L, "");
        }

        private StructureStats add(long damage, String otherTargets) {
            String sample = sampleOtherTargets;
            if (sample.isBlank() && !otherTargets.isBlank()) {
                sample = otherTargets;
            }
            return new StructureStats(hitCount + 1, damageTotal + damage, sample);
        }
    }

    private record EvidenceAgeStats(
            long hitCount,
            long damageTotal,
            String sample
    ) {
        private EvidenceAgeStats() {
            this(0L, 0L, "");
        }

        private EvidenceAgeStats add(long damage, String sampleValue) {
            String resolvedSample = sample;
            if (resolvedSample.isBlank() && !sampleValue.isBlank()) {
                resolvedSample = sampleValue;
            }
            return new EvidenceAgeStats(hitCount + 1L, damageTotal + damage, resolvedSample);
        }
    }

    private record TimelineStats(
            long totalDamage,
            long hitCount,
            long firstTimestamp,
            long lastTimestamp
    ) {
        private static TimelineStats add(TimelineStats existing, long damage, long timestamp) {
            if (existing == null) {
                return new TimelineStats(damage, 1L, timestamp, timestamp);
            }
            return new TimelineStats(
                    existing.totalDamage + damage,
                    existing.hitCount + 1L,
                    Math.min(existing.firstTimestamp, timestamp),
                    Math.max(existing.lastTimestamp, timestamp)
            );
        }
    }

    private record TimelineWindow(
            int targetId,
            long startMs,
            long endMs
    ) {
    }

    private record AlignedLocalEvent(
            long timestampMs,
            long targetId,
            String targetName,
            long amount,
            DamageType damageType,
            int actionId
    ) {
    }

    private record AlignedFflogsEvent(
            long timestampMs,
            int targetId,
            long amount,
            Integer hitType,
            int abilityGameId
    ) {
    }

    private record TargetAlignment(
            int fflogsTargetId,
            TimelineStats fflogsStats,
            Long localTargetId,
            TimelineStats localStats
    ) {
    }

}
