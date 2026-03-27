package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityRegressionGateTest {

    private static final double MAX_ALLOWED_P95_APE = 0.05;
    private static final double MAX_ALLOWED_ACTOR_APE = 0.05;
    private static final double MAX_ALLOWED_OUTLIER_RATIO = 0.0;
    private static final String HEAVY4_SUBMISSION = "2026-03-15-heavy4-vafpbaqjnhbk1mtw";
    private static final String LINDWURM_SUBMISSION = "2026-03-16-lindwurm-f8-bT1pkq7x4dhV3QGz";
    private static final String HEAVY2_SUBMISSION = "2026-03-18-heavy2-f6-fM4NVcGvb7aRjzCt";
    private static final double MAX_ALLOWED_HEAVY2_ALL_FIGHTS_P95_APE = 0.055;
    private static final double MAX_ALLOWED_HEAVY2_ALL_FIGHTS_MAX_APE = 0.060;
    private static final Properties APPLICATION_YAML = loadApplicationYaml();

    @Test
    void parityQualityRollup_staysWithinRegressionGate() throws Exception {
        SubmissionParityReportService reportService = buildConfiguredService();
        SubmissionParityQualityService qualityService = new SubmissionParityQualityService(reportService);
        SubmissionParityQualityService.SubmissionParityQualityRollup rollup = qualityService.buildRollup();

        assertTrue(
                rollup.p95AbsolutePercentageError() <= MAX_ALLOWED_P95_APE,
                "p95 APE gate failed: " + rollup.p95AbsolutePercentageError()
        );
        assertTrue(
                rollup.maxAbsolutePercentageError() <= MAX_ALLOWED_ACTOR_APE,
                "max actor APE gate failed: " + rollup.maxAbsolutePercentageError()
        );
        assertTrue(
                rollup.gate().actualOutlierRatio() <= MAX_ALLOWED_OUTLIER_RATIO,
                "outlier ratio gate failed: " + rollup.gate().actualOutlierRatio()
        );

        var bySubmissionId = rollup.submissions().stream()
                .collect(Collectors.toMap(
                        SubmissionParityQualityService.SubmissionQualityEntry::submissionId,
                        Function.identity()
                ));

        assertSubmissionQuality(
                bySubmissionId,
                HEAVY4_SUBMISSION,
                8,
                0.03,
                0.04
        );
        assertSubmissionQuality(
                bySubmissionId,
                LINDWURM_SUBMISSION,
                8,
                0.02,
                0.02
        );
    }

    @Test
    void heavy2AllMeaningfulFights_stayWithinGeneralizationGate() throws Exception {
        SubmissionParityReportService reportService = buildConfiguredService();
        SubmissionParityReport baseline = reportService.buildReport(HEAVY2_SUBMISSION);

        for (SubmissionParityReport.FflogsFightSummary fight : baseline.fflogs().fights()) {
            if (fight.encounterId() <= 0 || fight.name() == null || fight.name().isBlank()
                    || "Unknown".equalsIgnoreCase(fight.name())) {
                continue;
            }
            SubmissionParityReport report = reportService.buildReportForFight(HEAVY2_SUBMISSION, fight.id());
            SubmissionParityReport.ParityQualitySummary quality = report.parityQuality();
            assertTrue(
                    quality.matchedActorCount() >= 8,
                    "heavy2 all-fights matched actor count failed for fight " + fight.id()
                            + ": " + quality.matchedActorCount()
            );
            assertTrue(
                    quality.p95AbsolutePercentageError() <= MAX_ALLOWED_HEAVY2_ALL_FIGHTS_P95_APE,
                    "heavy2 all-fights p95 gate failed for fight " + fight.id()
                            + ": " + quality.p95AbsolutePercentageError()
            );
            assertTrue(
                    quality.maxAbsolutePercentageError() <= MAX_ALLOWED_HEAVY2_ALL_FIGHTS_MAX_APE,
                    "heavy2 all-fights max gate failed for fight " + fight.id()
                            + ": " + quality.maxAbsolutePercentageError()
            );
        }
    }

    private static void assertSubmissionQuality(
            Map<String, SubmissionParityQualityService.SubmissionQualityEntry> bySubmissionId,
            String submissionId,
            int minMatchedActors,
            double maxP95Ape,
            double maxActorApe
    ) {
        SubmissionParityQualityService.SubmissionQualityEntry entry = bySubmissionId.get(submissionId);
        assertTrue(entry != null, "missing submission quality entry: " + submissionId);
        if (isExternalFflogsFailure(entry)) {
            return;
        }
        assertTrue(
                entry.matchedActorCount() >= minMatchedActors,
                "matched actor count gate failed for " + submissionId + ": " + entry.matchedActorCount()
        );
        assertTrue(
                entry.p95AbsolutePercentageError() <= maxP95Ape,
                "submission p95 gate failed for " + submissionId + ": " + entry.p95AbsolutePercentageError()
        );
        assertTrue(
                entry.maxAbsolutePercentageError() <= maxActorApe,
                "submission max actor gate failed for " + submissionId + ": " + entry.maxAbsolutePercentageError()
        );
    }

    private static boolean isExternalFflogsFailure(SubmissionParityQualityService.SubmissionQualityEntry entry) {
        return "fetch_failed".equalsIgnoreCase(entry.fflogsStatus());
    }

    private SubmissionParityReportService buildConfiguredService() {
        ObjectMapper objectMapper = new ObjectMapper();
        FflogsApiClient apiClient = buildConfiguredApiClient(objectMapper);
        return new SubmissionParityReportService(
                new ActLineParser(),
                objectMapper,
                new FflogsZoneLookup(objectMapper),
                territoryId -> Optional.empty(),
                apiClient
        );
    }

    private FflogsApiClient buildConfiguredApiClient(ObjectMapper objectMapper) {
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pacemeter.fflogs.client-id", "pace.fflogs.clientId");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pacemeter.fflogs.client-secret", "pace.fflogs.clientSecret");
        Assumptions.assumeTrue(
                clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank(),
                "FFLogs credentials are required for parity regression gate test"
        );
        FflogsTokenStore tokenStore = new FflogsTokenStore(objectMapper);
        setField(tokenStore, "clientId", clientId);
        setField(tokenStore, "clientSecret", clientSecret);
        FflogsApiClient apiClient = new FflogsApiClient(tokenStore, objectMapper);
        setField(apiClient, "defaultPartition", envOrProperty("PACE_FFLOGS_PARTITION", "pacemeter.fflogs.partition", "pace.fflogs.partition"));
        return apiClient;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to set field " + fieldName + " on " + target.getClass().getSimpleName(), e);
        }
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
}
