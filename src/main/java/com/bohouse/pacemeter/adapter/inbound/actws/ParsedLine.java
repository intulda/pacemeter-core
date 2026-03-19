package com.bohouse.pacemeter.adapter.inbound.actws;

import java.time.Instant;

public sealed interface ParsedLine permits NetworkAbilityRaw, DotTickRaw, DamageText, PrimaryPlayerChanged, CombatantAdded, BuffApplyRaw, BuffRemoveRaw, StatusSnapshotRaw, DotStatusSignalRaw, ZoneChanged, NetworkDeath, PartyList {
    Instant ts();
}
