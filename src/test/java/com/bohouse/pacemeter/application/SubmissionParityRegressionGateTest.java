package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsApiClient;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsTokenStore;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParityRegressionGateTest {

    private static final double MAX_ALLOWED_P95_APE = 0.05;
    private static final double MAX_ALLOWED_ACTOR_APE = 0.05;
    private static final double MAX_ALLOWED_OUTLIER_RATIO = 0.0;

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
        String clientId = envOrProperty("PACE_FFLOGS_CLIENT_ID", "pace.fflogs.clientId");
        String clientSecret = envOrProperty("PACE_FFLOGS_CLIENT_SECRET", "pace.fflogs.clientSecret");
        Assumptions.assumeTrue(
                clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank(),
                "FFLogs credentials are required for parity regression gate test"
        );
        return new FflogsApiClient(new FflogsTokenStore(objectMapper), objectMapper);
    }

    private static String envOrProperty(String envKey, String propertyKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return System.getProperty(propertyKey, "");
    }
}
