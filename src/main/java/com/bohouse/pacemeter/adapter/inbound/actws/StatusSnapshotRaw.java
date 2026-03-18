package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;
import java.util.List;

public record StatusSnapshotRaw(
        Instant ts,
        long actorId,
        String actorName,
        List<StatusEntry> statuses,
        String rawLine
) implements ParsedLine {

    public record StatusEntry(
            int statusId,
            String rawValueHex,
            long sourceId
    ) {
    }
}
