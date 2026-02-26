package com.bohouse.pacemeter.adapter.inbound.actws;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

@Component
public final class ActLineParser {
    private static final Pattern KEY8HEX = Pattern.compile("^[0-9A-Fa-f]{8}$");
    private static final Pattern AMOUNT = Pattern.compile("피해를\\s*(\\d+)");
    private static final Pattern TARGET = Pattern.compile("(.+?)에게\\s*피해를");
    private static final Pattern SOURCE = Pattern.compile("^(.+?)의 공격");

    public ParsedLine parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] p = line.split("\\|", -1);
        if (p.length < 2) return null;

        int typeCode;
        try { typeCode = Integer.parseInt(p[0]); }
        catch (NumberFormatException e) { return null; }

        Instant ts = parseInstant(p[1]);
        if (ts == null) return null;

        // 1: ChangeZone
        if (typeCode == 1) {
            if (p.length < 4) return null;
            int zoneId = (int) parseHexLong(p[2]);
            String zoneName = p[3];
            return new ZoneChanged(ts, zoneId, zoneName);
        }

        // 0: LogLine text
        if (typeCode == 0) {
            if (p.length < 5) return null;
            // opcode hex at p[2]
            int opcode;
            try { opcode = Integer.parseInt(p[2], 16); }
            catch (NumberFormatException e) { return null; }

            if (opcode != 0x12A9) return null; // only damage line for v1

            String msg = p[4];
            long amount = extractLong(AMOUNT, msg, 1, -1);
            if (amount <= 0) return null;

            String target = extractString(TARGET, msg, 1);
            String source = extractString(SOURCE, msg, 1); // may be null

            boolean direct = msg.contains("직격");
            boolean crit = msg.contains("극대화") || msg.contains("치명");

            return new DamageText(ts, source, target, amount, crit, direct, line, msg);
        }

        // 2: ChangePrimaryPlayer
        if (typeCode == 2) {
            if (p.length < 4) return null;
            long playerId = parseHexLong(p[2]);
            String name = p[3];
            return new PrimaryPlayerChanged(ts, playerId, name);
        }

        // 3: AddCombatant (owner_id at p[8] in your C#)
        if (typeCode == 3) {
            if (p.length < 9) return null;
            long id = parseHexLong(p[2]);
            String name = p[3];
            long ownerId = parseHexLong(p[6]);
            return new CombatantAdded(ts, id, name, ownerId, line);
        }

        // 26: StatusAdd (BuffApply)
        if (typeCode == 26) {
            if (p.length < 9) return null;
            int statusId = (int) parseHexLong(p[2]);
            String statusName = p[3];
            double duration = parseDouble(p[4], 0.0);
            long sourceId = parseHexLong(p[5]);
            String sourceName = p[6];
            long targetId = parseHexLong(p[7]);
            String targetName = p[8];
            return new BuffApplyRaw(ts, statusId, statusName, duration, sourceId, sourceName, targetId, targetName);
        }

        // 30: StatusRemove (BuffRemove)
        if (typeCode == 30) {
            if (p.length < 9) return null;
            int statusId = (int) parseHexLong(p[2]);
            String statusName = p[3];
            long sourceId = parseHexLong(p[5]);
            String sourceName = p[6];
            long targetId = parseHexLong(p[7]);
            String targetName = p[8];
            return new BuffRemoveRaw(ts, statusId, statusName, sourceId, sourceName, targetId, targetName);
        }

        // 21/22: NetworkAbility / NetworkAOEAbility
        if (typeCode == 21 || typeCode == 22) {
            if (p.length < 10) return null;
            long actorId = parseHexLong(p[2]);
            String actorName = p[3];
            int skillId = (int) parseHexLong(p[4]);
            String skillName = p[5];
            long targetId = parseHexLong(p[6]);
            String targetName = p[7];
            long damage = decodeDamage(p[9]);

            return new NetworkAbilityRaw(ts, typeCode, actorId, actorName, skillId, skillName, targetId, targetName, damage, line);
        }

        return null;
    }

    private static Instant parseInstant(String s) {
        try {
            // input: 2026-02-11T20:56:11.8120000+09:00
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long parseHexLong(String hex) {
        if (hex == null || hex.isBlank()) return 0;
        try { return Long.parseUnsignedLong(hex, 16); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * FFXIV NetworkAbility 데미지 디코딩.
     * p[9]의 8자리 hex 값: 0xAABBCCDD (AA=d3, BB=d2, CC=d1, DD=d0)
     *
     * d0(최하위 바이트)의 bit6(0x40)이 "shift" 플래그:
     *   - 0x40 set → 데미지 > 65535, 실제값 = (d3 << 16) | (d1 << 8) | d2
     *   - 0x40 unset → 일반 데미지, 실제값 = (d3 << 8) | d2
     */
    static long decodeDamage(String hex) {
        if (hex == null || hex.isBlank()) return 0;
        long raw;
        try { raw = Long.parseUnsignedLong(hex, 16); }
        catch (NumberFormatException e) { return 0; }

        int d0 = (int) (raw & 0xFF);
        int d1 = (int) ((raw >> 8) & 0xFF);
        int d2 = (int) ((raw >> 16) & 0xFF);
        int d3 = (int) ((raw >> 24) & 0xFF);

        if ((d0 & 0x40) != 0) {
            // big damage: 65536 이상
            return ((long) d3 << 16) | ((long) d1 << 8) | d2;
        }
        // normal damage
        return ((long) d3 << 8) | d2;
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return def; }
    }

    private static long extractLong(Pattern pat, String msg, int group, long def) {
        var m = pat.matcher(msg);
        if (!m.find()) return def;
        try { return Long.parseLong(m.group(group)); }
        catch (Exception e) { return def; }
    }

    private static String extractString(Pattern pat, String msg, int group) {
        var m = pat.matcher(msg);
        if (!m.find()) return null;
        String v = m.group(group);
        return v == null ? null : v.trim();
    }
}
