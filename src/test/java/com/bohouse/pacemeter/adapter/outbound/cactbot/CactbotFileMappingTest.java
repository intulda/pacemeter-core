package com.bohouse.pacemeter.adapter.outbound.cactbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CactbotFileMappingTest {

    private final CactbotFileMapping mapping = new CactbotFileMapping(new ObjectMapper());

    @Test
    void resolveTimelinePath_knownTerritory_returnsPath() {
        assertEquals("ui/raidboss/data/06-ew/raid/p1s.txt",
                mapping.resolveTimelinePath(1003).orElseThrow());
        assertEquals("ui/raidboss/data/07-dt/raid/r1s.txt",
                mapping.resolveTimelinePath(1226).orElseThrow());
    }

    @Test
    void resolveTimelinePath_unknownTerritory_returnsEmpty() {
        assertTrue(mapping.resolveTimelinePath(999999).isEmpty());
    }
}
