package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;
import java.util.List;

public record CombatantStatusSnapshotRaw(
        Instant ts,
        long actorId,
        String actorName,
        int jobId,
        long currentHp,
        long maxHp,
        List<StatusSnapshotRaw.StatusEntry> statuses,
        String rawLine
) implements ParsedLine {
}
