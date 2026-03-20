package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;
import java.util.List;

/**
 * type 37(ActionEffectResult)에서 추출한 DoT status/source 신호.
 */
public record DotStatusSignalRaw(
        Instant ts,
        long targetId,
        List<StatusSignal> signals,
        String rawLine
) implements ParsedLine {

    public record StatusSignal(int statusId, long sourceId) {
    }
}
