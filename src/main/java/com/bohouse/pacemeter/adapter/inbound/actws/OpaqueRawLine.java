package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * Parsed but currently-unmodeled ACT line.
 * Keeps parser from dropping known type codes while ingestion can safely ignore.
 */
public record OpaqueRawLine(
        Instant ts,
        int typeCode,
        String subtype,
        String rawLine
) implements ParsedLine {
}
