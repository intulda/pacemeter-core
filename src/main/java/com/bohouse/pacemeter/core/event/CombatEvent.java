package com.bohouse.pacemeter.core.event;

import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.BuffId;
import com.bohouse.pacemeter.core.model.DamageType;

/**
 * 전투 엔진이 처리하는 모든 이벤트의 상위 타입.
 *
 * "이벤트"란 전투 중 일어나는 하나하나의 사건을 말한다.
 * 예: 공격이 들어감, 버프가 걸림, 전투가 시작됨 등.
 *
 * 모든 이벤트는 {@code timestampMs}(전투 시작 이후 경과 밀리초)를 가진다.
 * 예: 전투 시작 3초 후의 이벤트라면 timestampMs = 3000
 *
 * sealed 인터페이스이므로, 아래 6가지 타입만 존재할 수 있다.
 * 이렇게 하면 새 이벤트 타입을 빠뜨리지 않고 처리할 수 있다.
 *
 * 전제 조건:
 * - 이벤트는 timestampMs 기준으로 오름차순 정렬되어 들어온다.
 * - Tick 이벤트는 어댑터(외부 연결 계층)가 약 250ms마다 주입한다.
 * - FightStart가 항상 맨 처음, FightEnd가 항상 맨 마지막에 온다.
 */
public sealed interface CombatEvent {

    long timestampMs();

    /**
     * 전투 시작을 알리는 이벤트.
     *
     * @param timestampMs 항상 0 (전투가 시작되는 시점이 기준점)
     * @param fightName   보스/콘텐츠 이름 (예: "절 알렉산더", "절 미래지")
     */
    record FightStart(long timestampMs, String fightName) implements CombatEvent {}

    /**
     * 누군가가 데미지를 준 이벤트.
     * ACT의 NetworkAbility / NetworkAOEAbility 로그 라인에서 변환된다.
     *
     * @param timestampMs 전투 시작 기준 경과 시간 (밀리초)
     * @param sourceId    데미지를 준 캐릭터 ID
     * @param sourceName  데미지를 준 캐릭터 이름 (예: "나루 나루")
     * @param targetId    데미지를 맞은 대상 ID (보통 보스)
     * @param actionId    사용한 스킬/기술 ID
     * @param amount      데미지 양 (게임 내부 계산이 끝난 후의 값)
     * @param damageType  데미지 분류 (직접공격, 도트, 소환수)
     */
    record DamageEvent(
            long timestampMs,
            ActorId sourceId,
            String sourceName,
            ActorId targetId,
            int actionId,
            long amount,
            DamageType damageType
    ) implements CombatEvent {}

    /**
     * 버프/상태효과가 대상에게 걸린 이벤트.
     *
     * 예: 학자가 전사에게 "연환계" 시전 -> BuffApply 이벤트 발생
     *
     * @param timestampMs 전투 시작 기준 경과 시간 (밀리초)
     * @param sourceId    버프를 건 캐릭터 ID
     * @param targetId    버프를 받은 캐릭터 ID
     * @param buffId      버프 고유 ID
     * @param durationMs  버프 지속시간 (밀리초). 0이면 무한 또는 알 수 없음
     */
    record BuffApply(
            long timestampMs,
            ActorId sourceId,
            ActorId targetId,
            BuffId buffId,
            long durationMs
    ) implements CombatEvent {}

    /**
     * 버프/상태효과가 대상에게서 사라진 이벤트.
     *
     * 예: "연환계" 버프 시간이 다 됨 -> BuffRemove 이벤트 발생
     *
     * @param timestampMs 전투 시작 기준 경과 시간 (밀리초)
     * @param sourceId    원래 버프를 건 캐릭터 ID
     * @param targetId    버프가 사라진 캐릭터 ID
     * @param buffId      버프 고유 ID
     */
    record BuffRemove(
            long timestampMs,
            ActorId sourceId,
            ActorId targetId,
            BuffId buffId
    ) implements CombatEvent {}

    /**
     * 주기적으로 발생하는 "틱" 이벤트 (약 250ms마다).
     *
     * 틱이 들어오면 엔진이 현재 상태의 스냅샷(사진 찍듯이 현재 데이터 복사)을
     * 만들어서 오버레이 화면에 보내준다.
     * 또한 오래된 데미지 데이터를 정리(슬라이딩 윈도우 관리)한다.
     *
     * @param timestampMs 전투 시작 기준 경과 시간 (밀리초)
     */
    record Tick(long timestampMs) implements CombatEvent {}

    /**
     * 전투 종료를 알리는 이벤트.
     *
     * @param timestampMs 전투 시작 기준 경과 시간 (밀리초)
     * @param kill        true면 보스 처치(클리어), false면 전멸(와이프)
     */
    record FightEnd(long timestampMs, boolean kill) implements CombatEvent {}
}
