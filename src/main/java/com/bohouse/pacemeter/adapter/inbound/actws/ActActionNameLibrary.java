package com.bohouse.pacemeter.adapter.inbound.actws;

import java.util.Map;

final class ActActionNameLibrary {

    private static final Map<Integer, String> ACTION_NAMES = Map.ofEntries(
            Map.entry(0x8C0, "spinning edge"),
            Map.entry(0x8C2, "gust slash"),
            Map.entry(0x8C6, "assassinate"),
            Map.entry(0x8C7, "throwing dagger"),
            Map.entry(0x8C8, "mug"),
            Map.entry(0x8CE, "death blossom"),
            Map.entry(0x8CF, "aeolian edge"),
            Map.entry(0x8D2, "trick attack"),
            Map.entry(0x8D9, "fuma shuriken"),
            Map.entry(0x8DA, "katon"),
            Map.entry(0x8DB, "raiton"),
            Map.entry(0x8DC, "hyoton"),
            Map.entry(0x8DD, "huton"),
            Map.entry(0x8DF, "suiton"),
            Map.entry(0xDEB, "armor crush"),
            Map.entry(0xDEE, "dream within a dream"),
            Map.entry(0x1CE9, "hellfrog medium"),
            Map.entry(0x1CEA, "bhavacakra"),
            Map.entry(0x4068, "hakke mijinsatsu"),
            Map.entry(0x406B, "goka mekkyaku"),
            Map.entry(0x406C, "hyosho ranryu"),
            Map.entry(0x64AE, "phantom kamaitachi"),
            Map.entry(0x64B0, "hollow nozuchi"),
            Map.entry(0x64B1, "forked raiju"),
            Map.entry(0x64B2, "fleeting raiju"),
            Map.entry(0x905D, "dokumori"),
            Map.entry(0x905E, "kunai's bane"),
            Map.entry(0x905F, "deathfrog medium"),
            Map.entry(0x9060, "zesho meppo"),
            Map.entry(0x9061, "tenri jindo"),

            Map.entry(0x5EDB, "dosis"),
            Map.entry(0x5EDC, "diagnosis"),
            Map.entry(0x5EDE, "prognosis"),
            Map.entry(0x5EE1, "phlegma"),
            Map.entry(0x5EE3, "eukrasian diagnosis"),
            Map.entry(0x5EE4, "eukrasian prognosis"),
            Map.entry(0x5EE5, "eukrasian dosis"),
            Map.entry(0x5EE8, "druochole"),
            Map.entry(0x5EE9, "dyskrasia"),
            Map.entry(0x5EEB, "ixochole"),
            Map.entry(0x5EED, "pepsis"),
            Map.entry(0x5EEF, "taurochole"),
            Map.entry(0x5EF0, "toxikon"),
            Map.entry(0x5EF2, "dosis ii"),
            Map.entry(0x5EF3, "phlegma ii"),
            Map.entry(0x53F4, "eukrasian dosis ii"),
            Map.entry(0x5EF6, "holos"),
            Map.entry(0x5EF8, "dosis iii"),
            Map.entry(0x5EF9, "phlegma iii"),
            Map.entry(0x5EFA, "eukrasian dosis iii"),
            Map.entry(0x5EFB, "dyskrasia ii"),
            Map.entry(0x5EFC, "toxikon ii"),
            Map.entry(0x5EFE, "pneuma attack"),
            Map.entry(0x6B84, "pneuma heal"),
            Map.entry(0x90A8, "eukrasian dyskrasia"),
            Map.entry(0x90A9, "psyche"),
            Map.entry(0x90AA, "eukrasian prognosis ii"),

            Map.entry(0x004B, "true thrust"),
            Map.entry(0x004E, "vorpal thrust"),
            Map.entry(0x0054, "full thrust"),
            Map.entry(0x0056, "doom spike"),
            Map.entry(0x0057, "disembowel"),
            Map.entry(0x0058, "chaos thrust"),
            Map.entry(0x005A, "piercing talon"),
            Map.entry(0x005C, "jump"),
            Map.entry(0x0060, "dragonfire dive"),
            Map.entry(0x0DE2, "fang and claw"),
            Map.entry(0x0DE3, "geirskogul"),
            Map.entry(0x0DE4, "wheeling thrust"),
            Map.entry(0x1CE5, "sonic thrust"),
            Map.entry(0x1CE7, "mirage dive"),
            Map.entry(0x1CE8, "nastrond"),
            Map.entry(0x405D, "coerthan torment"),
            Map.entry(0x405E, "high jump"),
            Map.entry(0x405F, "raiden thrust"),
            Map.entry(0x4060, "stardiver"),
            Map.entry(0x64AA, "draconian fury"),
            Map.entry(0x64AB, "heavens' thrust"),
            Map.entry(0x64AC, "chaotic spring"),
            Map.entry(0x64AD, "wyrmwind thrust"),
            Map.entry(0x9058, "drakesbane"),
            Map.entry(0x9059, "rise of the dragon"),
            Map.entry(0x905A, "lance barrage"),
            Map.entry(0x905B, "spiral blow"),
            Map.entry(0x905C, "starcross"),

            Map.entry(0x00A7, "energy drain"),
            Map.entry(0x00B9, "adloquium"),
            Map.entry(0x00BA, "succor"),
            Map.entry(0x00BD, "lustrate"),
            Map.entry(0x00BE, "physick"),
            Map.entry(0x0322, "embrace"),
            Map.entry(0x0DFF, "indomitability"),
            Map.entry(0x0E00, "broil"),
            Map.entry(0x1D0B, "broil ii"),
            Map.entry(0x409B, "art of war"),
            Map.entry(0x409D, "broil iii"),
            Map.entry(0x409F, "fey blessing"),
            Map.entry(0x40A0, "fey blessing"),
            Map.entry(0x40A2, "consolation"),
            Map.entry(0x40A3, "consolation"),
            Map.entry(0x40A4, "seraphic veil"),
            Map.entry(0x45CD, "ruin-scholar"),
            Map.entry(0x45CE, "ruin ii"),
            Map.entry(0x6509, "broil iv"),
            Map.entry(0x650A, "art of war ii"),
            Map.entry(0x650B, "protraction"),
            Map.entry(0x9095, "concitation"),
            Map.entry(0x9097, "manifestation"),
            Map.entry(0x9098, "accession")
    );

    private ActActionNameLibrary() {
    }

    static String resolve(int skillId, String rawSkillName) {
        if (!isPlaceholder(rawSkillName)) {
            return rawSkillName;
        }
        return ACTION_NAMES.getOrDefault(skillId, rawSkillName);
    }

    private static boolean isPlaceholder(String rawSkillName) {
        if (rawSkillName == null || rawSkillName.isBlank()) {
            return true;
        }
        if (!rawSkillName.startsWith("Player")) {
            return false;
        }
        for (int i = "Player".length(); i < rawSkillName.length(); i++) {
            if (!Character.isDigit(rawSkillName.charAt(i))) {
                return false;
            }
        }
        return rawSkillName.length() > "Player".length();
    }
}
