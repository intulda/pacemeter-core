package com.bohouse.pacemeter.adapter.outbound.fflogsapi;

import java.util.Map;
import java.util.Optional;

/**
 * FFXIV JobID → FFLogs className 매핑.
 *
 * JobID는 ACT 로그의 Type 03 (AddCombatant) [4] 필드에서 추출한 hex 값.
 * FFLogs className은 영문 직업명 (예: "Dancer", "BlackMage").
 */
public final class FfxivJobMapper {

    // JobID (hex) → FFLogs className 매핑
    private static final Map<Integer, String> JOB_MAP = Map.ofEntries(
            // Tank
            Map.entry(0x13, "Paladin"),       // 19 팔라딘
            Map.entry(0x15, "Warrior"),       // 21 전사
            Map.entry(0x20, "DarkKnight"),    // 32 암흑기사
            Map.entry(0x25, "Gunbreaker"),    // 37 건브레이커

            // Healer
            Map.entry(0x18, "WhiteMage"),     // 24 백마도사
            Map.entry(0x1C, "Scholar"),       // 28 학자
            Map.entry(0x21, "Astrologian"),   // 33 점성술사
            Map.entry(0x28, "Sage"),          // 40 현자

            // Melee DPS
            Map.entry(0x14, "Monk"),          // 20 몽크
            Map.entry(0x16, "Dragoon"),       // 22 용기사
            Map.entry(0x1E, "Ninja"),         // 30 닌자
            Map.entry(0x22, "Samurai"),       // 34 사무라이
            Map.entry(0x27, "Reaper"),        // 39 리퍼
            Map.entry(0x2D, "Viper"),         // 45 바이퍼

            // Physical Ranged DPS
            Map.entry(0x17, "Bard"),          // 23 음유시인
            Map.entry(0x1F, "Machinist"),     // 31 기공사
            Map.entry(0x26, "Dancer"),        // 38 무도가

            // Magical Ranged DPS
            Map.entry(0x19, "BlackMage"),     // 25 흑마도사
            Map.entry(0x1B, "Summoner"),      // 27 소환사
            Map.entry(0x23, "RedMage"),       // 35 적마도사
            Map.entry(0x2A, "Pictomancer")    // 42 화가
    );

    /**
     * JobID를 FFLogs className으로 변환.
     *
     * @param jobId ACT 로그의 JobID (hex 값)
     * @return FFLogs className (예: "Dancer")
     */
    public static Optional<String> toClassName(int jobId) {
        return Optional.ofNullable(JOB_MAP.get(jobId));
    }

    /**
     * JobID를 직업 한글명으로 변환 (디버깅용).
     */
    public static String toKoreanName(int jobId) {
        return switch (jobId) {
            case 0x13 -> "나이트";
            case 0x14 -> "몽크";
            case 0x15 -> "전사";
            case 0x16 -> "용기사";
            case 0x17 -> "음유시인";
            case 0x18 -> "백마도사";
            case 0x19 -> "흑마도사";
            case 0x1B -> "소환사";
            case 0x1C -> "학자";
            case 0x1E -> "닌자";
            case 0x1F -> "기공사";
            case 0x20 -> "암흑기사";
            case 0x21 -> "점성술사";
            case 0x22 -> "사무라이";
            case 0x23 -> "적마도사";
            case 0x25 -> "건브레이커";
            case 0x26 -> "무도가";
            case 0x27 -> "리퍼";
            case 0x28 -> "현자";
            case 0x2A -> "화가";
            case 0x2D -> "바이퍼";
            default -> "Unknown(" + Integer.toHexString(jobId) + ")";
        };
    }
}