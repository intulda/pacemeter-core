package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record CombatantAdded(
        Instant ts,
        long id,
        String name,
        int jobId,
        long ownerId,
        long currentHp,
        long maxHp,
        String rawLine
) implements ParsedLine {
}
