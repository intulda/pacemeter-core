package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * ACT LogLine typeCode 24: DoT tick damage.
 */
public record DotTickRaw(
        Instant ts,
        long targetId,
        String targetName,
        int statusId,
        long sourceId,
        String sourceName,
        long damage,
        String rawLine
) implements ParsedLine {
}
