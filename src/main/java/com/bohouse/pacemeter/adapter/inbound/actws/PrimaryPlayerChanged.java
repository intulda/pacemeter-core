package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record PrimaryPlayerChanged(Instant ts, long playerId, String playerName) implements ParsedLine {
}
