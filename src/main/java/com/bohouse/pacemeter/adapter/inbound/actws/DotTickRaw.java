package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * ACT LogLine typeCode 24: DoT tick damage.
 */
public record DotTickRaw(
        Instant ts,
        long targetId,
        String targetName,
        String effectType,
        int statusId,
        long sourceId,
        String sourceName,
        long damage,
        String rawLine
) implements ParsedLine {
    public boolean isDot() {
        return "DoT".equalsIgnoreCase(effectType);
    }

    public boolean hasKnownStatus() {
        return statusId != 0;
    }

    public boolean isHot() {
        return "HoT".equalsIgnoreCase(effectType);
    }
}
