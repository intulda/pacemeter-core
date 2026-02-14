package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * ACT LogLine typeCode 26: StatusAdd (버프 적용)
 * rawLine 형식: 26|timestamp|statusId|statusName|duration|sourceId|sourceName|targetId|targetName|...
 */
public record BuffApplyRaw(
        Instant ts,
        int statusId,
        String statusName,
        double durationSec,
        long sourceId,
        String sourceName,
        long targetId,
        String targetName
) implements ParsedLine {}
