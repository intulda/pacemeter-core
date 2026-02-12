package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record CombatantAdded(Instant ts, long id, String name, long ownerId) implements ParsedLine {
}
