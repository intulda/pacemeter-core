package com.bohouse.pacemeter.adapter.outbound.cactbot;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CactbotTimelineProviderIntegrationTest {

    private static final int P1S_TERRITORY_ID = 1003;
    private static final String ENABLE_PROPERTY = "pacemeter.networkTests";
    private static final String ENABLE_ENV = "PACEMETER_RUN_NETWORK_TESTS";

    @Test
    void getEnrageTime_downloadsAndParsesRealTimeline() {
        assumeTrue(networkTestsEnabled(),
                () -> "Enable with -D" + ENABLE_PROPERTY + "=true or " + ENABLE_ENV + "=true");

        CactbotTimelineProvider provider = new CactbotTimelineProvider(
                restTemplateWithTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(5)),
                new CactbotFileMapping(new ObjectMapper())
        );

        EnrageTimeProvider.EnrageInfo enrageInfo = provider.getEnrageTime(P1S_TERRITORY_ID).orElseThrow();

        assertTrue(enrageInfo.seconds() > 60.0, "expected a real raid enrage time");
        assertTrue(enrageInfo.source().endsWith("ui/raidboss/data/06-ew/raid/p1s.txt"));
        assertEquals(expectedConfidence(enrageInfo.seconds()), enrageInfo.confidence());
    }

    private static boolean networkTestsEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY)
                || "true".equalsIgnoreCase(System.getenv(ENABLE_ENV));
    }

    private static RestTemplate restTemplateWithTimeouts(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());
        return new RestTemplate(requestFactory);
    }

    private static EnrageTimeProvider.ConfidenceLevel expectedConfidence(double seconds) {
        if (seconds < 60.0) {
            return EnrageTimeProvider.ConfidenceLevel.LOW;
        }
        if (seconds < 180.0) {
            return EnrageTimeProvider.ConfidenceLevel.MEDIUM;
        }
        return EnrageTimeProvider.ConfidenceLevel.HIGH;
    }
}
