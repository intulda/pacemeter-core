package com.bohouse.pacemeter.core.model;

/**
 * DoT snapshot을 시작할 만한 상태이상인지 판정하는 최소 규칙.
 *
 * 현재는 locale 의존을 줄이기 위해 보수적 heuristic을 사용한다.
 * 추후 실제 status ID 카탈로그가 확보되면 ID 기반으로 교체/보강한다.
 */
public final class DotStatusLibrary {

    private static final long MIN_DOT_DURATION_MS = 12_000;

    private DotStatusLibrary() {
    }

    public static boolean isLikelyDot(BuffId buffId, String buffName, long durationMs, ActorId sourceId, ActorId targetId) {
        if (durationMs < MIN_DOT_DURATION_MS) {
            return false;
        }
        if (sourceId.equals(targetId)) {
            return false;
        }
        if (!isNpc(targetId)) {
            return false;
        }
        return RaidBuffLibrary.find(buffId, buffName).isEmpty();
    }

    private static boolean isNpc(ActorId actorId) {
        long value = actorId.value();
        return value >= 0x40000000L && value < 0x50000000L;
    }
}
