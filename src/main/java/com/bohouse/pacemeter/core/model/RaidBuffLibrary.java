package com.bohouse.pacemeter.core.model;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * rDPS 재분배에 사용할 레이드 버프 정의.
 *
 * 현재는 "직접 데미지에 배수로 작용하는 외부 버프"만 최소 지원한다.
 * FFLogs Live 쪽으로 확장할 때 crit/direct-hit/받는 피해 증가 디버프를 여기에 추가한다.
 */
public final class RaidBuffLibrary {

    private static final List<RaidBuffDefinition> DEFINITIONS = List.of(
            RaidBuffDefinition.directDamage(Set.of(new BuffId(0x74F)),
                    RaidBuffEffect.percentageDamage(0.06),
                    "The Balance",
                    "Balance",
                    "균형"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.06),
                    "Divination"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.05),
                    "Embolden"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.05),
                    "Technical Finish"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.05),
                    "Brotherhood"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.03),
                    "Arcane Circle"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.03),
                    "Searing Light"),
            RaidBuffDefinition.directDamage(Set.of(),
                    RaidBuffEffect.percentageDamage(0.05),
                    "Mug"),
            RaidBuffDefinition.critRate(Set.of(),
                    RaidBuffEffect.critRate(0.10),
                    "Battle Litany"),
            RaidBuffDefinition.critRate(Set.of(),
                    RaidBuffEffect.critRate(0.10),
                    "Chain Stratagem"),
            RaidBuffDefinition.directHitRate(Set.of(),
                    RaidBuffEffect.directHitRate(0.20),
                    "Battle Voice"),
            RaidBuffDefinition.mixedRates(Set.of(),
                    List.of(
                            RaidBuffEffect.critRate(0.20),
                            RaidBuffEffect.directHitRate(0.20)
                    ),
                    "Devilment")
    );
    private static final Map<BuffId, RaidBuffDefinition> BY_ID = buildIdIndex();
    private static final Map<String, RaidBuffDefinition> BY_NAME = buildNameIndex();

    private RaidBuffLibrary() {
    }

    public static Optional<RaidBuffDefinition> find(BuffId buffId, String buffName) {
        RaidBuffDefinition byId = BY_ID.get(buffId);
        if (byId != null) {
            return Optional.of(byId);
        }
        if (buffName == null || buffName.isBlank()) {
            return Optional.empty();
        }
        RaidBuffDefinition byName = BY_NAME.get(normalize(buffName));
        return byName != null ? Optional.of(byName) : Optional.empty();
    }

    private static Map<BuffId, RaidBuffDefinition> buildIdIndex() {
        java.util.HashMap<BuffId, RaidBuffDefinition> index = new java.util.HashMap<>();
        for (RaidBuffDefinition definition : DEFINITIONS) {
            for (BuffId id : definition.ids()) {
                index.put(id, definition);
            }
        }
        return Map.copyOf(index);
    }

    private static Map<String, RaidBuffDefinition> buildNameIndex() {
        java.util.HashMap<String, RaidBuffDefinition> index = new java.util.HashMap<>();
        for (RaidBuffDefinition definition : DEFINITIONS) {
            for (String alias : definition.aliases()) {
                index.put(normalize(alias), definition);
            }
        }
        return Map.copyOf(index);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public record RaidBuffEffect(Kind kind, double amount) {
        public enum Kind {
            PERCENT_DAMAGE,
            CRIT_RATE,
            DIRECT_HIT_RATE
        }

        public static RaidBuffEffect percentageDamage(double amount) {
            return new RaidBuffEffect(Kind.PERCENT_DAMAGE, amount);
        }

        public static RaidBuffEffect critRate(double amount) {
            return new RaidBuffEffect(Kind.CRIT_RATE, amount);
        }

        public static RaidBuffEffect directHitRate(double amount) {
            return new RaidBuffEffect(Kind.DIRECT_HIT_RATE, amount);
        }
    }

    public record RaidBuffDefinition(Set<BuffId> ids, List<RaidBuffEffect> effects, Set<String> aliases) {
        public static RaidBuffDefinition directDamage(Set<BuffId> ids, RaidBuffEffect effect, String... aliases) {
            return new RaidBuffDefinition(ids, List.of(effect), Set.of(aliases));
        }

        public static RaidBuffDefinition critRate(Set<BuffId> ids, RaidBuffEffect effect, String... aliases) {
            return new RaidBuffDefinition(ids, List.of(effect), Set.of(aliases));
        }

        public static RaidBuffDefinition directHitRate(Set<BuffId> ids, RaidBuffEffect effect, String... aliases) {
            return new RaidBuffDefinition(ids, List.of(effect), Set.of(aliases));
        }

        public static RaidBuffDefinition mixedRates(Set<BuffId> ids, List<RaidBuffEffect> effects, String... aliases) {
            return new RaidBuffDefinition(ids, List.copyOf(effects), Set.of(aliases));
        }
    }
}
