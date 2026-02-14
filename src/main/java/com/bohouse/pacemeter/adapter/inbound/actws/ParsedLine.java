package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public sealed interface ParsedLine permits NetworkAbilityRaw, DamageText, PrimaryPlayerChanged, CombatantAdded, BuffApplyRaw, BuffRemoveRaw {
    Instant ts();
}
