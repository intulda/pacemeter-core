package com.bohouse.pacemeter.adapter.inbound.actws;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public final class ActLineParser {
    private static final Pattern KEY8HEX = Pattern.compile("^[0-9A-Fa-f]{8}$");
    private static final Pattern AMOUNT = Pattern.compile("피해를\\s*(\\d+)");
    private static final Pattern TARGET = Pattern.compile("(.+?)에게\\s*피해를");
    private static final Pattern SOURCE = Pattern.compile("^(.+?)의 공격");
    private static final Pattern PRIVATE_USE_ICON = Pattern.compile("[\\uE000-\\uF8FF]");
    private static final Pattern AMOUNT_KR_V2 = Pattern.compile("\uD53C\uD574\uB97C\\s*(\\d+)");
    private static final Pattern TARGET_KR_V2 = Pattern.compile("(.+?)\uC5D0\uAC8C\\s*\uD53C\uD574\uB97C");
    private static final Pattern SOURCE_KR_V2 = Pattern.compile("^(.+?)\uC758\\s*\uACF5\uACA9");
    private static final Pattern AMOUNT_KR = Pattern.compile("피해를\\s*(\\d+)");
    private static final Pattern TARGET_KR = Pattern.compile("(.+?)에게\\s*피해를");
    private static final Pattern SOURCE_KR = Pattern.compile("^(.+?)의\\s*공격");

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

            if (!isDamageTextOpcode(opcode)) return null;

            String msg = p[4];
            String normalizedMessage = normalizeDamageTextMessage(msg);
            long amount = extractLong(AMOUNT_KR_V2, normalizedMessage, 1, -1);
            if (amount <= 0) return null;

            String source = normalizeCombatTextName(extractString(SOURCE_KR_V2, normalizedMessage, 1));
            String targetSection = normalizedMessage;
            String targetSourcePrefix = source == null || source.isBlank()
                    ? ""
                    : "^\\s*" + Pattern.quote(source) + "의\\s*공격\\s*";
            if (source != null && !source.isBlank()) {
                targetSection = normalizedMessage.replaceFirst("^\\s*" + Pattern.quote(source) + "의 공격\\s*", "");
            }
            String targetSourcePrefixV2 = source == null || source.isBlank()
                    ? ""
                    : "^\\s*" + Pattern.quote(source) + "\uC758\\s*\uACF5\uACA9\\s*";
            if (!targetSourcePrefixV2.isBlank()) {
                targetSection = normalizedMessage.replaceFirst(targetSourcePrefixV2, "");
            }
            if (!targetSourcePrefix.isBlank()) {
                targetSection = normalizedMessage.replaceFirst(targetSourcePrefix, "");
            }
            String target = normalizeCombatTextName(extractString(TARGET_KR_V2, targetSection, 1));

            boolean direct = msg.contains("직격");
            boolean crit = msg.contains("극대화") || msg.contains("치명");

            direct = direct || msg.contains("직격");
            crit = crit || msg.contains("극대") || msg.contains("치명");
            direct = direct || msg.contains("\uC9C1\uACA9");
            crit = crit || msg.contains("\uADF9\uB300") || msg.contains("\uCE58\uBA85");
            return new DamageText(ts, source, target, amount, crit, direct, line, msg);
        }

        // 2: ChangePrimaryPlayer
        if (typeCode == 2) {
            if (p.length < 4) return null;
            long playerId = parseHexLong(p[2]);
            String name = p[3];
            return new PrimaryPlayerChanged(ts, playerId, name);
        }

        // 11: PartyList
        // Format: 11|timestamp|partyCount|id0|id1|id2|id3|id4|id5|id6|id7
        if (typeCode == 11) {
            if (p.length < 3) return null;
            int partyCount = Integer.parseInt(p[2]);
            List<Long> memberIds = new ArrayList<>();
            for (int i = 0; i < partyCount && i < 8 && (3 + i) < p.length; i++) {
                long memberId = parseHexLong(p[3 + i]);
                if (memberId != 0) {
                    memberIds.add(memberId);
                }
            }
            return new PartyList(ts, memberIds);
        }

        // 3: AddCombatant
        // Format: 03|ts|id|name|jobId|level|ownerId|...
        if (typeCode == 3) {
            if (p.length < 12) return null;
            long id = parseHexLong(p[2]);
            String name = p[3];
            int jobId = (int) parseHexLong(p[4]);
            long ownerId = parseHexLong(p[6]);
            long currentHp = parseDecimalLong(p[10]);
            long maxHp = parseDecimalLong(p[11]);
            return new CombatantAdded(ts, id, name, jobId, ownerId, currentHp, maxHp, line);
        }

        // 261 Add: key/value combatant payload
        if (typeCode == 261) {
            if (p.length < 6 || !"Add".equals(p[2])) return null;
            long id = parseHexLong(p[3]);
            Map<String, String> fields = parseKeyValueFields(p, 4);
            String name = fields.getOrDefault("Name", "");
            int jobId = (int) parseHexLong(fields.get("Job"));
            long ownerId = parseHexLong(fields.get("OwnerID"));
            long currentHp = parseDecimalLong(fields.get("CurrentHP"));
            long maxHp = parseDecimalLong(fields.get("MaxHP"));
            return new CombatantAdded(ts, id, name, jobId, ownerId, currentHp, maxHp, line);
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

        if (typeCode == 38) {
            if (p.length < 19) return null;
            long actorId = parseHexLong(p[2]);
            String actorName = p[3];
            List<StatusSnapshotRaw.StatusEntry> statuses = new ArrayList<>();
            for (int i = 18; i + 2 < p.length - 1; i += 3) {
                long packedStatus = parseHexLong(p[i]);
                int statusId = (int) (packedStatus & 0xFFFFL);
                if (statusId == 0) {
                    continue;
                }
                statuses.add(new StatusSnapshotRaw.StatusEntry(statusId, p[i + 1], parseHexLong(p[i + 2])));
            }
            return new StatusSnapshotRaw(ts, actorId, actorName, List.copyOf(statuses), line);
        }

        // 21/22: NetworkAbility / NetworkAOEAbility
        if (typeCode == 21 || typeCode == 22) {
            if (p.length < 10) return null;
            long actorId = parseHexLong(p[2]);
            String actorName = p[3];
            int skillId = (int) parseHexLong(p[4]);
            String skillName = ActActionNameLibrary.resolve(skillId, p[5]);
            long targetId = parseHexLong(p[6]);
            String targetName = p[7];
            long actionFlags = parseHexLong(p[8]);
            long damage = decodeDamage(p[9]);
            boolean criticalHit = (actionFlags & 0x2000L) != 0;
            boolean directHit = (actionFlags & 0x4000L) != 0;

            return new NetworkAbilityRaw(
                    ts,
                    typeCode,
                    actorId,
                    actorName,
                    skillId,
                    skillName,
                    targetId,
                    targetName,
                    criticalHit,
                    directHit,
                    damage,
                    line
            );
        }

        // 24: DoT tick
        // Example:
        // 24|ts|targetId|targetName|DoT|0|damageHex|...|sourceId|sourceName|...
        if (typeCode == 24) {
            if (p.length < 19) return null;
            long targetId = parseHexLong(p[2]);
            String targetName = p[3];
            String effectType = p[4];
            int statusId = (int) parseHexLong(p[5]);
            long damage = parseHexLong(p[6]);
            long sourceId = parseHexLong(p[17]);
            String sourceName = p[18];

            if (sourceId == 0 || sourceName == null || sourceName.isBlank() || damage <= 0) {
                return null;
            }
            return new DotTickRaw(ts, targetId, targetName, effectType, statusId, sourceId, sourceName, damage, line);
        }

        // 25: NetworkDeath
        if (typeCode == 25) {
            if (p.length < 4) return null;
            long targetId = parseHexLong(p[2]);
            String targetName = p[3];
            return new NetworkDeath(ts, targetId, targetName);
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

    private static long parseDecimalLong(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * FFXIV NetworkAbility 데미지 디코딩.
     * 공식 LogGuide 기준:
     * - 일반 데미지: 첫 2바이트(AA BB) 사용
     * - 큰 데미지: raw & 0x00004000 != 0 이면 DD AA BB 사용
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

        if ((raw & 0x00004000L) != 0) {
            return ((long) d0 << 16) | ((long) d3 << 8) | d2;
        }
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

    private static String normalizeDamageTextMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String normalized = PRIVATE_USE_ICON.matcher(message).replaceAll(" ");
        normalized = normalized.replace("직격!", " ");
        normalized = normalized.replace("극대화!", " ");
        normalized = normalized.replace("치명타!", " ");
        normalized = normalized.replace("직격!", " ");
        normalized = normalized.replace("극대화!", " ");
        normalized = normalized.replace("치명타!", " ");
        normalized = normalized.replace("\uC9C1\uACA9!", " ");
        normalized = normalized.replace("\uADF9\uB300\uD654!", " ");
        normalized = normalized.replace("\uCE58\uBA85\uD0C0!", " ");
        return normalized.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeCombatTextName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = PRIVATE_USE_ICON.matcher(value).replaceAll(" ");
        normalized = normalized.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean isDamageTextOpcode(int opcode) {
        return (opcode & 0xFF) == 0xA9;
    }

    private static Map<String, String> parseKeyValueFields(String[] parts, int startIndex) {
        Map<String, String> fields = new HashMap<>();
        for (int i = startIndex; i + 1 < parts.length; i += 2) {
            String key = parts[i];
            String value = parts[i + 1];
            if (key == null || key.isBlank()) {
                continue;
            }
            fields.put(key, value);
        }
        return fields;
    }
}
