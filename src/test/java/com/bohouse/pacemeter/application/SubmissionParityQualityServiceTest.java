package com.bohouse.pacemeter.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityQualityServiceTest {

    @Test
    void buildRollup_scansSubmissionDirectories_andCollectsFailures() throws Exception {
        Path root = Files.createTempDirectory("parity-quality-test");
        Files.createDirectories(root.resolve("ok-one"));
        Files.createDirectories(root.resolve("ok-two"));
        Files.createDirectories(root.resolve("bad-one"));

        Map<String, SubmissionParityReport> reports = new HashMap<>();
        reports.put("ok-one", stubReport("ok-one", "ok", 2, 8, 0.02, 0.03, 0.04, 0));
        reports.put("ok-two", stubReport("ok-two", "ok", 5, 6, 0.05, 0.06, 0.08, 2));

        SubmissionParityReportService stubService = new SubmissionParityReportService(null, null, null, null, null) {
            @Override
            public SubmissionParityReport buildReport(String submissionId) throws IOException {
                if ("bad-one".equals(submissionId)) {
                    throw new IOException("failed to read");
                }
                SubmissionParityReport report = reports.get(submissionId);
                if (report == null) {
                    throw new IOException("missing report");
                }
                return report;
            }
        };

        SubmissionParityQualityService service = new SubmissionParityQualityService(stubService, root);
        SubmissionParityQualityService.SubmissionParityQualityRollup rollup = service.buildRollup();

        assertEquals(3, rollup.submissionsScanned());
        assertEquals(2, rollup.submissionsEvaluated());
        assertEquals(1, rollup.submissionsFailed());
        assertEquals(0, rollup.actorComparisons());
        assertEquals(2, rollup.submissions().size());
        assertEquals(1, rollup.failures().size());
        assertEquals(0, rollup.worstActors().size());
        assertEquals(0, rollup.jobs().size());
        assertTrue(rollup.gate() != null);
        assertTrue(rollup.gate().pass());
        assertEquals("bad-one", rollup.failures().get(0).submissionId());
        assertTrue(rollup.generatedAt() != null && !rollup.generatedAt().isBlank());
    }

    private static SubmissionParityReport stubReport(
            String submissionId,
            String fflogsStatus,
            Integer selectedFightId,
            int matchedActorCount,
            double mape,
            double p95,
            double max,
            int outliers
    ) {
        return new SubmissionParityReport(
                new SubmissionParityReport.SubmissionMetadata(
                        submissionId,
                        "2026-03-18T00:00:00Z",
                        "KR",
                        "ko",
                        1327,
                        "encounter",
                        "savage",
                        List.of("NIN"),
                        "https://ko.fflogs.com/reports/xxx",
                        selectedFightId,
                        "",
                        true,
                        ""
                ),
                new SubmissionParityReport.ReplaySummary("combat.log", 1, 1, 0, true, 1000),
                new SubmissionParityReport.DamageTextMatchDiagnostics(0, 0, 0, 0, 0),
                null,
                new SubmissionParityReport.FflogsReportSummary(
                        fflogsStatus,
                        "https://ko.fflogs.com/reports/xxx",
                        "xxx",
                        selectedFightId,
                        "Lindwurm",
                        "kill",
                        0L,
                        1000L,
                        List.of(),
                        List.of(),
                        null
                ),
                new SubmissionParityReport.ParityQualitySummary(
                        matchedActorCount,
                        0,
                        0,
                        mape,
                        p95,
                        max,
                        outliers,
                        0.4,
                        0.7,
                        0.9
                ),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
