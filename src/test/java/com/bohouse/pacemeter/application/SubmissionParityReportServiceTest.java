package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
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

        SubmissionParityReport report = service.buildReport("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt");

        assertEquals("2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt", report.metadata().submissionId());
        assertTrue(report.replay().parsedLines() > 0);
        assertNotNull(report.damageTextMatchDiagnostics());
        assertNotNull(report.combat());
        assertFalse(report.combat().actors().isEmpty());
        assertNotNull(report.combat().boss());
        assertNotNull(report.combat().fightName());
        assertFalse(report.combat().fightName().isBlank());
        assertTrue(report.combat().actors().stream().anyMatch(actor -> actor.totalDamage() > 0.0));
        assertEquals("no_token_configured", report.fflogs().status());
        assertEquals("fM4NVcGvb7aRjzCt", report.fflogs().reportCode());
        assertNotNull(report.parityQuality());
        assertEquals(0, report.parityQuality().matchedActorCount());
        assertEquals(0.0, report.parityQuality().meanAbsolutePercentageError());
        assertEquals(report.unmatchedLocalActors().size(), report.parityQuality().unmatchedLocalActorCount());
        assertEquals(report.unmatchedFflogsActors().size(), report.parityQuality().unmatchedFflogsActorCount());
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
        assertNotNull(report.parityQuality());
        assertEquals(0, report.parityQuality().matchedActorCount());
        assertEquals(0.0, report.parityQuality().p95AbsolutePercentageError());
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
                null,
                1_773_563_660_543L,
                "2026-03-15T17:34:20+09:00"
        );

        assertNotNull(selectedFight);
        assertEquals(2, selectedFight.id());
    }

    @Test
    void chooseFight_prefersExpectedEncounterIdOverSubmittedAtHeuristic() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        List<FflogsApiClient.ReportFight> fights = List.of(
                new FflogsApiClient.ReportFight(2, "Lindwurm", 49_499L, 440_399L, true, 104),
                new FflogsApiClient.ReportFight(5, "Lindwurm", 758_392L, 1_285_394L, true, 105)
        );

        Method chooseFight = SubmissionParityReportService.class.getDeclaredMethod(
                "chooseFight",
                List.class,
                String.class,
                Integer.class,
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
                105,
                1_773_563_660_543L,
                "2026-03-15T17:34:20+09:00"
        );

        assertNotNull(selectedFight);
        assertEquals(5, selectedFight.id());
    }

    @Test
    void toSubmissionFflogsSummary_keepsExplicitFightIdEvenWhenEncounterMismatch() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        Method method = SubmissionParityReportService.class.getDeclaredMethod(
                "toSubmissionFflogsSummary",
                String.class,
                Integer.class,
                int.class,
                String.class,
                FflogsApiClient.ReportSummary.class
        );
        method.setAccessible(true);

        FflogsApiClient.ReportSummary summary = new FflogsApiClient.ReportSummary(
                "dummy",
                1_000_000L,
                List.of(
                        new FflogsApiClient.ReportFight(2, "Encounter-105", 100_000L, 200_000L, true, 105),
                        new FflogsApiClient.ReportFight(6, "Encounter-104", 300_000L, 450_000L, true, 104)
                )
        );

        SubmissionParityReport.FflogsReportSummary result =
                (SubmissionParityReport.FflogsReportSummary) method.invoke(
                        service,
                        "https://ko.fflogs.com/reports/VAfPBaqJnHbK1Mtw",
                        6,
                        1327,
                        "2026-03-15T17:34:20+09:00",
                        summary
                );

        assertNotNull(result);
        assertEquals(6, result.selectedFightId());
        assertEquals("Encounter-104", result.selectedFightName());
    }

    @Test
    void chooseFight_prefersKillWhenExpectedEncounterMatchesWipeAndKill() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        List<FflogsApiClient.ReportFight> fights = List.of(
                new FflogsApiClient.ReportFight(4, "Lindwurm", 584_604L, 733_027L, false, 105),
                new FflogsApiClient.ReportFight(5, "Lindwurm", 758_392L, 1_285_394L, true, 105)
        );

        Method chooseFight = SubmissionParityReportService.class.getDeclaredMethod(
                "chooseFight",
                List.class,
                String.class,
                Integer.class,
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
                105,
                1_773_563_660_543L,
                "2026-03-15T17:34:20+09:00"
        );

        assertNotNull(selectedFight);
        assertEquals(5, selectedFight.id());
    }

    @Test
    void toSubmissionFflogsSummary_keepsExplicitFightIdWhenEncounterMismatches() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        FflogsApiClient.ReportSummary summary = new FflogsApiClient.ReportSummary(
                "report-code",
                1_773_563_660_543L,
                List.of(
                        new FflogsApiClient.ReportFight(6, "Lindwurm", 2_450_000L, 2_800_000L, true, 104),
                        new FflogsApiClient.ReportFight(2, "Lindwurm", 49_499L, 440_399L, true, 105)
                )
        );

        Method toSubmissionFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "toSubmissionFflogsSummary",
                String.class,
                Integer.class,
                int.class,
                String.class,
                FflogsApiClient.ReportSummary.class
        );
        toSubmissionFflogsSummary.setAccessible(true);

        SubmissionParityReport.FflogsReportSummary result =
                (SubmissionParityReport.FflogsReportSummary) toSubmissionFflogsSummary.invoke(
                        service,
                        "https://ko.fflogs.com/reports/report-code",
                        6,
                        1327,
                        "2026-03-15T17:34:20+09:00",
                        summary
                );

        assertEquals(6, result.selectedFightId());
    }

    @Test
    void toSubmissionFflogsSummary_keepsSelectedFightWhenSubmittedAtIsNearFightStart() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        long reportStart = 1_773_563_660_543L;
        FflogsApiClient.ReportSummary summary = new FflogsApiClient.ReportSummary(
                "report-code",
                reportStart,
                List.of(
                        new FflogsApiClient.ReportFight(2, "Lindwurm", 49_499L, 440_399L, true, 104),
                        new FflogsApiClient.ReportFight(5, "Lindwurm", 758_392L, 1_285_394L, true, 105)
                )
        );

        Method toSubmissionFflogsSummary = SubmissionParityReportService.class.getDeclaredMethod(
                "toSubmissionFflogsSummary",
                String.class,
                Integer.class,
                int.class,
                String.class,
                FflogsApiClient.ReportSummary.class
        );
        toSubmissionFflogsSummary.setAccessible(true);

        SubmissionParityReport.FflogsReportSummary result =
                (SubmissionParityReport.FflogsReportSummary) toSubmissionFflogsSummary.invoke(
                        service,
                        "https://ko.fflogs.com/reports/report-code",
                        2,
                        1327,
                        "2026-03-15T17:35:00+09:00",
                        summary
                );

        assertEquals(2, result.selectedFightId());
    }

    @Test
    void resolveExpectedEncounterId_usesExplicitTerritoryOverrideBeforeZoneIndexHeuristic() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        Method resolveExpectedEncounterId = SubmissionParityReportService.class.getDeclaredMethod(
                "resolveExpectedEncounterId",
                int.class
        );
        resolveExpectedEncounterId.setAccessible(true);

        Integer expectedEncounterId = (Integer) resolveExpectedEncounterId.invoke(service, 1327);

        assertEquals(105, expectedEncounterId);
    }

    @Test
    void deanonymizeLine_restoresAliasedSkillAndBuffNamesFromMapping() throws Exception {
        SubmissionParityReportService service = new SubmissionParityReportService(
                new ActLineParser(),
                new ObjectMapper(),
                new FflogsZoneLookup(new ObjectMapper()),
                territoryId -> Optional.empty(),
                new FflogsApiClient(new FflogsTokenStore(new ObjectMapper()), new ObjectMapper())
        );

        Method deanonymizeLine = SubmissionParityReportService.class.getDeclaredMethod(
                "deanonymizeLine",
                String.class,
                Map.class
        );
        deanonymizeLine.setAccessible(true);

        String restoredAbility = (String) deanonymizeLine.invoke(
                service,
                "21|2026-03-15T17:34:20.5430000+09:00|1013CC4B|나성|5EF8|Player127|4000664C|린드블룸|750003|AE4C0000",
                Map.of("Player127", "도시스 3")
        );
        String restoredBuff = (String) deanonymizeLine.invoke(
                service,
                "26|2026-03-15T17:35:16.4600000+09:00|E65|Player149|20.00|1008B280|이끼이끼|100B73AC|생쥐|00|226548|203793|f17cb73eead0b4ee",
                Map.of("Player149", "하늘 구현")
        );

        assertTrue(restoredAbility.contains("|도시스 3|"));
        assertTrue(restoredBuff.contains("|하늘 구현|"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
