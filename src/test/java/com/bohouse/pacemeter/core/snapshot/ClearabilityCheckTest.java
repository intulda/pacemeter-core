package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClearabilityCheckTest {

    @Test
    void calculate_canClearWhenProjectedKillIsBeforeEnrage() {
        ClearabilityCheck result = ClearabilityCheck.calculate(
                1_000_000L,
                500_000L,
                50_000L,
                new EnrageTimeProvider.EnrageInfo(
                        120.0,
                        EnrageTimeProvider.ConfidenceLevel.HIGH,
                        "test"
                )
        );

        assertTrue(result.canClear());
        assertEquals(100.0, result.estimatedKillTimeSeconds(), 0.001);
        assertEquals(120.0, result.enrageTimeSeconds(), 0.001);
        assertEquals(20.0, result.marginSeconds(), 0.001);
        assertEquals(8_333.333, result.requiredDps(), 0.001);
        assertEquals(EnrageTimeProvider.ConfidenceLevel.LOW, result.confidence());
    }

    @Test
    void calculate_cannotClearWhenProjectedKillIsAfterEnrage() {
        ClearabilityCheck result = ClearabilityCheck.calculate(
                900_000L,
                90_000L,
                30_000L,
                new EnrageTimeProvider.EnrageInfo(
                        180.0,
                        EnrageTimeProvider.ConfidenceLevel.MEDIUM,
                        "test"
                )
        );

        assertFalse(result.canClear());
        assertEquals(300.0, result.estimatedKillTimeSeconds(), 0.001);
        assertEquals(180.0, result.enrageTimeSeconds(), 0.001);
        assertEquals(-120.0, result.marginSeconds(), 0.001);
        assertEquals(5_000.0, result.requiredDps(), 0.001);
        assertEquals(EnrageTimeProvider.ConfidenceLevel.LOW, result.confidence());
    }

    @Test
    void calculate_returnsInfiniteKillTimeWhenNoDamageYet() {
        ClearabilityCheck result = ClearabilityCheck.calculate(
                1_500_000L,
                0L,
                45_000L,
                new EnrageTimeProvider.EnrageInfo(
                        90.0,
                        EnrageTimeProvider.ConfidenceLevel.LOW,
                        "test"
                )
        );

        assertFalse(result.canClear());
        assertTrue(Double.isInfinite(result.estimatedKillTimeSeconds()));
        assertTrue(Double.isInfinite(-result.marginSeconds()));
        assertEquals(16_666.666, result.requiredDps(), 0.001);
        assertEquals(EnrageTimeProvider.ConfidenceLevel.LOW, result.confidence());
    }

    @Test
    void classifyConfidence_usesElapsedFightTimeThresholds() {
        assertEquals(EnrageTimeProvider.ConfidenceLevel.LOW, ClearabilityCheck.classifyConfidence(59_999));
        assertEquals(EnrageTimeProvider.ConfidenceLevel.MEDIUM, ClearabilityCheck.classifyConfidence(60_000));
        assertEquals(EnrageTimeProvider.ConfidenceLevel.MEDIUM, ClearabilityCheck.classifyConfidence(179_999));
        assertEquals(EnrageTimeProvider.ConfidenceLevel.HIGH, ClearabilityCheck.classifyConfidence(180_000));
    }
}
