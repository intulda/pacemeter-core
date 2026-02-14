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
        // 데미지 > 65535일 때 d0의 bit6(0x40)이 설정됨
        // 예: 실제 데미지 = 0x020FA6 = 134054
        // 인코딩: d3=0x02, d2=0xA6, d1=0x0F, d0=0x40
        // → raw = 0x02A60F40
        // → (d3 << 16) | (d1 << 8) | d2 = (0x02 << 16) | (0x0F << 8) | 0xA6
        //   = 131072 + 3840 + 166 = 135078
        long dmg = ActLineParser.decodeDamage("02A60F40");
        assertEquals((0x02 << 16) | (0x0F << 8) | 0xA6, dmg);
        assertTrue(dmg > 65535, "big damage should exceed 65535");
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

    // ── 무시 대상 ──

    @Test
    void parse_unknownTypeCode_returnsNull() {
        String line = "251|" + TS + "|some|data|here|more";
        assertNull(parser.parse(line));
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
}
