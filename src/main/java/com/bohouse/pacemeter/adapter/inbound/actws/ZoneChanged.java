package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record ZoneChanged(Instant ts, int zoneId, String zoneName) implements ParsedLine {}
