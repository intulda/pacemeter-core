package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record PlayerStatsUpdated(
        Instant ts,
        int jobId,
        String rawLine
) implements ParsedLine {
}
