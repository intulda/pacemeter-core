package com.bohouse.pacemeter.core.model;

/**
 * 게임 내 캐릭터(플레이어, 보스, 소환수 등)를 구분하는 고유 ID.
 * ACT 로그에서 가져온 숫자 값을 그대로 감싸서 사용한다.
 *
 * 예: 전사 캐릭터의 ID가 1이면 -> new ActorId(1)
 */
public record ActorId(long value) {
}
