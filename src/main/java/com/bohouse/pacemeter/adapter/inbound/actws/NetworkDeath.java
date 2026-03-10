package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record NetworkDeath(Instant ts, long targetId, String targetName) implements ParsedLine {
}