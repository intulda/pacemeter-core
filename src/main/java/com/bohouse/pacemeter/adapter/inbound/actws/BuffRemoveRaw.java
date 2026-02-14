package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

/**
 * ACT LogLine typeCode 30: StatusRemove (버프 제거)
 * rawLine 형식: 30|timestamp|statusId|statusName|0|sourceId|sourceName|targetId|targetName|...
 */
public record BuffRemoveRaw(
        Instant ts,
        int statusId,
        String statusName,
        long sourceId,
        String sourceName,
        long targetId,
        String targetName
) implements ParsedLine {}
