package com.bohouse.pacemeter.core.model;

/**
 * 데미지의 종류를 크게 3가지로 분류한다.
 *
 * - DIRECT : 직접 공격 (오토어택, GCD 스킬, oGCD 스킬 등)
 * - DOT    : 도트 데미지 (지속 피해, 예: 바이오, 썬더 틱 데미지)
 * - PET    : 소환수가 준 데미지 (카벙클, 요정 등)
 *
 * MVP 단계에서는 크리티컬/다이렉트히트 같은 세부 구분은 하지 않는다.
 * 그건 나중에 rDPS를 더 정밀하게 계산할 때 추가할 예정.
 */
public enum DamageType {
    DIRECT,
    DOT,
    PET
}
