package com.bohouse.pacemeter.adapter.outbound.cactbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CactbotTimelineProviderTest {

    @Test
    void parseEnrageSeconds_extractsValue() {
        String timeline = """
                18.0 "Raidwide"
                598.7 "Ultimate Annihilation (enrage)"
                """;

        assertEquals(598.7, CactbotTimelineProvider.parseEnrageSeconds(timeline).orElseThrow(), 0.001);
    }

    @Test
    void parseEnrageSeconds_withoutEnrage_returnsEmpty() {
        String timeline = """
                18.0 "Raidwide"
                45.5 "Tankbuster"
                """;

        assertTrue(CactbotTimelineProvider.parseEnrageSeconds(timeline).isEmpty());
    }
}
