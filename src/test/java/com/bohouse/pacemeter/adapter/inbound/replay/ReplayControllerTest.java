package com.bohouse.pacemeter.adapter.inbound.replay;

import com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.inbound.actws.PrimaryPlayerChanged;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ReplayControllerTest {

    @Test
    void fallbackPrimaryPlayerChange_returnsPrimaryPlayerWhenNameMatches() {
        Instant ts = Instant.parse("2026-02-11T12:00:00Z");
        ParsedLine parsed = new CombatantAdded(
                ts,
                0x101EF25BL,
                "한정서너나좋아싫어",
                0x26,
                0,
                188_893L,
                188_893L,
                "03|..."
        );

        PrimaryPlayerChanged result = ReplayController.fallbackPrimaryPlayerChange(
                parsed,
                "한정서너나좋아싫어",
                false
        ).orElseThrow();

        assertEquals(ts, result.ts());
        assertEquals(0x101EF25BL, result.playerId());
        assertEquals("한정서너나좋아싫어", result.playerName());
    }

    @Test
    void fallbackPrimaryPlayerChange_skipsWhenPlayerAlreadySet() {
        ParsedLine parsed = new CombatantAdded(
                Instant.parse("2026-02-11T12:00:00Z"),
                0x101EF25BL,
                "한정서너나좋아싫어",
                0x26,
                0,
                188_893L,
                188_893L,
                "03|..."
        );

        assertTrue(ReplayController.fallbackPrimaryPlayerChange(
                parsed,
                "한정서너나좋아싫어",
                true
        ).isEmpty());
    }

    @Test
    void fallbackPrimaryPlayerChange_skipsWhenNameDoesNotMatch() {
        ParsedLine parsed = new CombatantAdded(
                Instant.parse("2026-02-11T12:00:00Z"),
                0x101EF25BL,
                "다른이름",
                0x26,
                0,
                188_893L,
                188_893L,
                "03|..."
        );

        assertTrue(ReplayController.fallbackPrimaryPlayerChange(
                parsed,
                "한정서너나좋아싫어",
                false
        ).isEmpty());
    }
}
