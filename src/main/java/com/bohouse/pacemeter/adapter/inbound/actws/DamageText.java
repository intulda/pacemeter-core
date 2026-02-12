package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record DamageText(
        Instant ts,
        String sourceTextName,   // e.g. "구려카벙클" or null
        String targetTextName,   // e.g. "더 타이런트"
        long amount,
        boolean criticalLike,    // optional
        boolean directHitLike,   // optional
        String rawLine,
        String message
) implements ParsedLine { }
