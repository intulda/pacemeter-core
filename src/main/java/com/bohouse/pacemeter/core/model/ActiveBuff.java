package com.bohouse.pacemeter.core.model;

/**
 * 현재 캐릭터에게 걸려 있는 버프 하나를 나타낸다.
 *
 * 예를 들어, 학자가 전사에게 "연환계" 버프를 걸면:
 *   - buffId: 연환계의 고유 ID
 *   - sourceId: 학자의 ActorId (누가 걸었는지)
 *   - appliedAtMs: 전투 시작 후 몇 ms에 걸렸는지
 *   - durationMs: 버프 지속시간 (밀리초). 0이면 무한 또는 알 수 없음
 *
 * @param buffId      버프 고유 ID
 * @param sourceId    버프를 건 캐릭터 ID (나중에 rDPS 기여도 계산에 필요)
 * @param appliedAtMs 전투 시작 기준, 버프가 걸린 시점 (밀리초)
 * @param durationMs  버프 지속시간 (밀리초). 0이면 무한 또는 알 수 없음
 */
public record ActiveBuff(
        BuffId buffId,
        ActorId sourceId,
        long appliedAtMs,
        long durationMs
) {
}
