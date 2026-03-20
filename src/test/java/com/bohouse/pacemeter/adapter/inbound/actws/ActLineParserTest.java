package com.bohouse.pacemeter.adapter.inbound.actws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActLineParserTest {

    private final ActLineParser parser = new ActLineParser();

    private static final String TS = "2026-02-11T20:56:11.8120000+09:00";

    // ── typeCode 21: NetworkAbility ──

    @Test
    void parse_networkAbility_typeCode21() {
        String line = "21|" + TS + "|1000000A|Warrior Main|B4|Fast Blade|40000001|Boss|" +
                "710003|1B50000|0|0|0|0|0|0|0|0|0|0|0|0|12345678|0|0|0";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(NetworkAbilityRaw.class, result);
        NetworkAbilityRaw raw = (NetworkAbilityRaw) result;
        assertEquals(0x1000000AL, raw.actorId());
        assertEquals("Warrior Main", raw.actorName());
        assertEquals(0xB4, raw.skillId());
        assertEquals("Fast Blade", raw.skillName());
        assertEquals(0x40000001L, raw.targetId());
        assertEquals("Boss", raw.targetName());
        assertTrue(raw.damage() > 0);
    }

    @Test
    void parse_networkAbility_realPayload() {
        // 실제 ACT에서 수신된 rawLine
        String line = "21|" + TS + "|101396A7|다르윈|E21|강렬한 참격|400006CB|나무인형|710003|31C20000|1B|E218000|0|0|0|0|0|0|0|0|0|0|0|0|2105135|3460000|0|10000|||-808.19|-575.03|8.00|0.23";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(NetworkAbilityRaw.class, result);
        NetworkAbilityRaw raw = (NetworkAbilityRaw) result;
        assertEquals("다르윈", raw.actorName());
        assertEquals("강렬한 참격", raw.skillName());
        assertEquals("나무인형", raw.targetName());
        assertTrue(raw.damage() > 0, "데미지가 0보다 커야 함: " + raw.damage());
    }

    @Test
    void parse_networkAbility_ignoresSupplementalEffectSlots() {
        String line = "21|" + TS + "|1008B280|이끼이끼|8776|Player162|4000664C|린드블룸|756003|8D864002|1B|87768000|0|0|0|0|0|0|0|0|0|0|0|0";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(NetworkAbilityRaw.class, result);
        NetworkAbilityRaw raw = (NetworkAbilityRaw) result;
        assertEquals(0x028D86, raw.damage());
    }

    @Test
    void parse_networkAbility_replacesKnownPlayerPlaceholdersWithDefinitionNames() {
        ParsedLine ninjaResult = parser.parse(
                "21|" + TS + "|100B73AC|생쥐|8C0|Player99|4000664C|린드블룸|714003|7F7C0000"
        );
        ParsedLine sageResult = parser.parse(
                "21|" + TS + "|1013CC4B|나성|5EF8|Player127|4000664C|린드블룸|750003|AE4C0000"
        );
        ParsedLine dragoonResult = parser.parse(
                "21|" + TS + "|101589A6|치삐|405E|Player164|4000664C|린드블룸|724003|FE090000"
        );
        ParsedLine unknownResult = parser.parse(
                "21|" + TS + "|100B73AC|생쥐|49B9|Player36|4000664C|린드블룸|714003|10094001"
        );

        assertInstanceOf(NetworkAbilityRaw.class, ninjaResult);
        assertInstanceOf(NetworkAbilityRaw.class, sageResult);
        assertInstanceOf(NetworkAbilityRaw.class, dragoonResult);
        assertInstanceOf(NetworkAbilityRaw.class, unknownResult);

        assertEquals("spinning edge", ((NetworkAbilityRaw) ninjaResult).skillName());
        assertEquals("dosis iii", ((NetworkAbilityRaw) sageResult).skillName());
        assertEquals("high jump", ((NetworkAbilityRaw) dragoonResult).skillName());
        assertEquals("Player36", ((NetworkAbilityRaw) unknownResult).skillName());
    }

    @Test
    void parse_dotTick_typeCode24() {
        String line = "24|" + TS + "|40005E82|더 타이런트|DoT|0|EBAC|142805740|154287371|10000|10000|||99.96|100.39|0.00|3.12|101C2E9E|돌체라떼|FFFFFFFF|121751|185402|7220|10000|||101.49|108.00|0.00|-2.53|8c8069c0758193c7";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(DotTickRaw.class, result);
        DotTickRaw raw = (DotTickRaw) result;
        assertEquals(0x40005E82L, raw.targetId());
        assertEquals("더 타이런트", raw.targetName());
        assertEquals("DoT", raw.effectType());
        assertEquals(0, raw.statusId());
        assertEquals(0x101C2E9EL, raw.sourceId());
        assertEquals("돌체라떼", raw.sourceName());
        assertEquals(0xEBACL, raw.damage());
    }

    @Test
    void parse_dotTick_typeCode24_withUnknownSourceStillParses() {
        String line = "24|" + TS + "|400034E9|린드블룸|DoT|0|57D7|80500036|104593118|10000|10000|||100.00|85.00|0.00|0.00|E0000000||FFFFFFFF|||||||||||f0b2a1132a271953";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(DotTickRaw.class, result);
        DotTickRaw raw = (DotTickRaw) result;
        assertEquals(0xE0000000L, raw.sourceId());
        assertEquals("", raw.sourceName());
        assertEquals(0x57D7L, raw.damage());
    }

    // ── 데미지 디코딩 ──

    @Test
    void decodeDamage_normalDamage() {
        // 0x31C20000: d3=0x31, d2=0xC2, d0=0x00 (no shift)
        // damage = (0x31 << 8) | 0xC2 = 12738
        long dmg = ActLineParser.decodeDamage("31C20000");
        assertEquals(12738, dmg);
    }

    @Test
    void decodeDamage_bigDamage_shiftFlag() {
        // 공식 LogGuide 예시: 423F400F -> 999999 (0x0F423F)
        long dmg = ActLineParser.decodeDamage("423F400F");
        assertEquals(999999, dmg);
        assertTrue(dmg > 65535, "big damage should exceed 65535");
    }

    @Test
    void decodeDamage_highUpperByteWithShiftFlag_fallsBackToShortDamage() {
        long dmg = ActLineParser.decodeDamage("5BDC4016");
        assertEquals(0x5BDCL, dmg);
    }

    @Test
    void decodeDamage_null_returnsZero() {
        assertEquals(0, ActLineParser.decodeDamage(null));
        assertEquals(0, ActLineParser.decodeDamage(""));
    }

    // ── typeCode 26: BuffApply ──

    @Test
    void parse_buffApply_typeCode26() {
        // 26|ts|statusId(hex)|statusName|duration|sourceId(hex)|sourceName|targetId(hex)|targetName|...
        String line = "26|" + TS + "|74F|The Balance|15.00|1000000B|Astrologian|1000000A|Warrior Main|1E|28D2|0";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(BuffApplyRaw.class, result);
        BuffApplyRaw raw = (BuffApplyRaw) result;
        assertEquals(0x74F, raw.statusId());
        assertEquals("The Balance", raw.statusName());
        assertEquals(15.0, raw.durationSec(), 0.01);
        assertEquals(0x1000000BL, raw.sourceId());
        assertEquals("Astrologian", raw.sourceName());
        assertEquals(0x1000000AL, raw.targetId());
        assertEquals("Warrior Main", raw.targetName());
    }

    @Test
    void parse_buffApply_zeroDuration() {
        String line = "26|" + TS + "|1E7|Shield Oath|0.00|1000000A|Paladin|1000000A|Paladin|0|0|0";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(BuffApplyRaw.class, result);
        BuffApplyRaw raw = (BuffApplyRaw) result;
        assertEquals(0.0, raw.durationSec(), 0.01);
    }

    @Test
    void parse_buffApply_tooFewFields_returnsNull() {
        // 9개 미만 필드
        String line = "26|" + TS + "|74F|The Balance|15.00|1000000B|Astrologian|1000000A";
        assertNull(parser.parse(line));
    }

    // ── typeCode 30: BuffRemove ──

    @Test
    void parse_buffRemove_typeCode30() {
        String line = "30|" + TS + "|74F|The Balance|0.00|1000000B|Astrologian|1000000A|Warrior Main|1E|28D2|0";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(BuffRemoveRaw.class, result);
        BuffRemoveRaw raw = (BuffRemoveRaw) result;
        assertEquals(0x74F, raw.statusId());
        assertEquals("The Balance", raw.statusName());
        assertEquals(0x1000000BL, raw.sourceId());
        assertEquals("Astrologian", raw.sourceName());
        assertEquals(0x1000000AL, raw.targetId());
        assertEquals("Warrior Main", raw.targetName());
    }

    @Test
    void parse_buffRemove_tooFewFields_returnsNull() {
        String line = "30|" + TS + "|74F|The Balance|0.00|1000000B|Astrologian|1000000A";
        assertNull(parser.parse(line));
    }

    @Test
    void parse_statusSnapshot_typeCode38_extractsTrackedStatuses() {
        String line = "38|" + TS + "|100B73AC|생쥐|0064641E|226548|226548|10000|10000|36||100.40|99.20|0.00|1.52|0|0|0|0A0168|41F00000|E0000000|29CE0030|450E6719|100B73AC|0720|42700000|101EF25B|077D|418E55EB|40006875|0839|422AA96F|101EF25B|29D60031|41B80820|100B73AC|0312|41988312|101589A6|0552|41E9374B|10128857|0301F0|40B5FBE6|100B73AC|0D25|41F00000|1013CC4B|0BBB|41A00000|1013CC4B|0|0|0|0737|422AA96F|101EF25B|012B|407E9784|100B744D|0E65|4196624C|1008B280|708392671ac69ade";

        ParsedLine result = parser.parse(line);

        assertInstanceOf(StatusSnapshotRaw.class, result);
        StatusSnapshotRaw raw = (StatusSnapshotRaw) result;
        assertEquals(0x100B73ACL, raw.actorId());
        assertEquals("생쥐", raw.actorName());
        assertTrue(raw.statuses().stream().anyMatch(status -> status.statusId() == 0x0030 && status.sourceId() == 0x100B73ACL));
        assertTrue(raw.statuses().stream().anyMatch(status -> status.statusId() == 0x0839 && status.sourceId() == 0x101EF25BL));
        assertTrue(raw.statuses().stream().anyMatch(status -> status.statusId() == 0x0312 && status.sourceId() == 0x101589A6L));
        assertTrue(raw.statuses().stream().anyMatch(status -> status.statusId() == 0x0E65 && status.sourceId() == 0x1008B280L));
    }

    @Test
    void parse_dotStatusSignal_typeCode37_readsAlignedEffectSlots() {
        String line = "37|" + TS + "|102B1904|재탄|000000D0|226588|226588|10000|10000|0||99.50|110.31|0.00|3.14|2202|0|0|03|1E000767|03|C1A00000|102B1904|1F000A38|0|C1F00000|102B1904|200004AF|14|41F00000|101F2C6F|hash";

        ParsedLine result = parser.parse(line);

        assertInstanceOf(DotStatusSignalRaw.class, result);
        DotStatusSignalRaw signal = (DotStatusSignalRaw) result;
        assertEquals(0x102B1904L, signal.targetId());
        assertTrue(signal.signals().size() >= 2);
        assertEquals(0x0767, signal.signals().get(0).statusId());
        assertEquals(0x102B1904L, signal.signals().get(0).sourceId());
        assertEquals(0x0A38, signal.signals().get(1).statusId());
        assertEquals(0x102B1904L, signal.signals().get(1).sourceId());
    }

    @Test
    void parse_dotStatusSignal_typeCode37_ignoresMalformedOverlappingSlots() {
        String line = "37|" + TS + "|101F2C6F|도리도리|000000CC|207706|207706|10000|10000|0||99.41|112.70|0.00|3.11|2600|0|0|01|071F|0|42700000|101F2C6F|hash";

        ParsedLine result = parser.parse(line);

        assertNull(result);
    }

    // ── 무시 대상 ──

    @Test
    void parse_unknownTypeCode_returnsNull() {
        String line = "251|" + TS + "|some|data|here|more";
        assertNull(parser.parse(line));
    }

    @Test
    void parse_damageText_koreanCritDirectMessage() {
        String message = "\uBCF4\uC2A4\uC5D0\uAC8C \uC9C1\uACA9! \uADF9\uB300\uD654! \uD53C\uD574\uB97C 6026 \uC8FC\uC5C8\uC2B5\uB2C8\uB2E4";
        String line = "00|" + TS + "|12A9||" + message + "|deadbeef";

        ParsedLine result = parser.parse(line);

        assertInstanceOf(DamageText.class, result);
        DamageText raw = (DamageText) result;
        assertNull(raw.sourceTextName());
        assertEquals("\uBCF4\uC2A4", raw.targetTextName());
        assertEquals(6026L, raw.amount());
        assertTrue(raw.criticalLike());
        assertTrue(raw.directHitLike());
    }

    @Test
    void parse_damageText_koreanSourceAndTargetMessage() {
        String message = "\uC0DD\uC950\uC758 \uACF5\uACA9 \uBCF4\uC2A4\uC5D0\uAC8C \uD53C\uD574\uB97C 12345 \uC8FC\uC5C8\uC2B5\uB2C8\uB2E4";
        String line = "00|" + TS + "|12A9||" + message + "|deadbeef";

        ParsedLine result = parser.parse(line);

        assertInstanceOf(DamageText.class, result);
        DamageText raw = (DamageText) result;
        assertEquals("\uC0DD\uC950", raw.sourceTextName());
        assertEquals("\uBCF4\uC2A4", raw.targetTextName());
        assertEquals(12345L, raw.amount());
        assertFalse(raw.criticalLike());
        assertFalse(raw.directHitLike());
    }

    @Test
    void parse_typeCode260_returnsNull() {
        String line = "260|" + TS + "|some|data|here|more";
        assertNull(parser.parse(line));
    }

    // ── 엣지 케이스 ──

    @Test
    void parse_null_returnsNull() {
        assertNull(parser.parse(null));
    }

    @Test
    void parse_blank_returnsNull() {
        assertNull(parser.parse("   "));
    }

    @Test
    void parse_invalidTimestamp_returnsNull() {
        String line = "26|not-a-timestamp|74F|The Balance|15.00|1000000B|Astrologian|1000000A|Warrior Main";
        assertNull(parser.parse(line));
    }

    @Test
    void parse_damageText_acceptsAlternateA9Opcodes() {
        String line = "00|" + TS + "|0AA9||생쥐의 공격 \ue06f 직격! 린드블룸에게 피해를 8268 주었습니다.|checksum";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(DamageText.class, result);
        DamageText text = (DamageText) result;
        assertEquals(8268L, text.amount());
        assertTrue(text.directHitLike());
    }

    @Test
    void parse_damageText_normalizesTargetAndSourceNames() {
        String line = "00|" + TS + "|12A9||한정서너나좋아싫어펜리르의 공격 \ue06f 극대화! 린드블룸에게 피해를 14226 주었습니다.|checksum";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(DamageText.class, result);
        DamageText text = (DamageText) result;
        assertEquals("한정서너나좋아싫어펜리르", text.sourceTextName());
        assertEquals("린드블룸", text.targetTextName());
        assertTrue(text.criticalLike());
    }

    @Test
    void parse_networkAbility_decodesCriticalAndDirectFlagsFromActionField() {
        String line = "21|" + TS + "|100B73AC|생쥐|07|공격|4000664C|린드블룸|716003|414A0000";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(NetworkAbilityRaw.class, result);
        NetworkAbilityRaw ability = (NetworkAbilityRaw) result;
        assertTrue(ability.criticalHit());
        assertTrue(ability.directHit());
        assertEquals(16714L, ability.damage());
    }

    // ── typeCode 2: PrimaryPlayerChanged ──

    @Test
    void parse_primaryPlayerChanged() {
        String line = "2|" + TS + "|1000000A|Warrior Main";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(PrimaryPlayerChanged.class, result);
        PrimaryPlayerChanged p = (PrimaryPlayerChanged) result;
        assertEquals(0x1000000AL, p.playerId());
        assertEquals("Warrior Main", p.playerName());
    }

    @Test
    void parse_combatantAdded_withHpFields() {
        String line = "3|" + TS + "|400006CB|나무인형|0|0|0|0|0|0|123456789|234567890";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(CombatantAdded.class, result);
        CombatantAdded combatant = (CombatantAdded) result;
        assertEquals(0x400006CBL, combatant.id());
        assertEquals("나무인형", combatant.name());
        assertEquals(123456789L, combatant.currentHp());
        assertEquals(234567890L, combatant.maxHp());
    }

    @Test
    void parse_combatantAdded_from261Add_withOwner() {
        String line = "261|" + TS + "|Add|400066A3|BNpcID|6DF|BNpcNameID|35000000|CurrentMP|256|Heading|0.0000|Level|185|MaxHP|215512|Name|Player137|OwnerID|1008B280|PosX|99.9398|PosY|104.7190|Type|11|WorldID|6400|checksum";
        ParsedLine result = parser.parse(line);

        assertInstanceOf(CombatantAdded.class, result);
        CombatantAdded combatant = (CombatantAdded) result;
        assertEquals(0x400066A3L, combatant.id());
        assertEquals("Player137", combatant.name());
        assertEquals(0x1008B280L, combatant.ownerId());
        assertEquals(215512L, combatant.maxHp());
    }
}
