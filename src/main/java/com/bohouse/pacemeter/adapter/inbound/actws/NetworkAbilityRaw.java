package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public record NetworkAbilityRaw(
        Instant ts,
        int typeCode, // 21 or 22
        long actorId,
        String actorName,
        int skillId,
        String skillName,
        long targetId,
        String targetName,
        long damage,
        String rawLine
) implements ParsedLine {
}