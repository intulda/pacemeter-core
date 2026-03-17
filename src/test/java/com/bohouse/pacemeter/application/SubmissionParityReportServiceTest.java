package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
}
