package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * type 37(ActionEffectResult)에서 추출한 DoT status/source 신호.
 */
public record DotStatusSignalRaw(
        Instant ts,
        long targetId,
        int statusId,
        long sourceId,
        String rawLine
) implements ParsedLine {
}
