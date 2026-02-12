package com.bohouse.pacemeter.adapter.inbound.actws;

public enum ActMessageType {
    LogLine(0),
    ChangePrimaryPlayer(2),
    AddCombatant(3),
    NetworkAbility(21),
    NetworkAOEAbility(22);

    public final int code;
    ActMessageType(int code) { this.code = code; }

    public static ActMessageType from(int code) {
        for (var t : values()) if (t.code == code) return t;
        return null;
    }
}
