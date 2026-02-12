package com.bohouse.pacemeter.core.snapshot;

/**
 * 현재 파티의 실제 데미지와 레퍼런스(기준) 페이스의 비교 결과.
 * 페이스 프로필이 로딩되어 있을 때만 스냅샷에 포함된다.
 *
 * 예: "상위 10% 파티 기준, 지금 5% 앞서고 있어요"
 *
 * @param profileLabel             레퍼런스 프로필 이름 (예: "절미래지_상위10%")
 * @param expectedCumulativeDamage 이 시점에서 레퍼런스 기준 기대 누적 데미지
 * @param actualCumulativeDamage   우리 파티의 실제 누적 데미지
 * @param deltaDamage              차이 = 실제 - 기대 (양수면 앞서고 있음, 음수면 뒤처짐)
 * @param deltaPercent             차이 비율(%) = 차이 / 기대 * 100 (양수면 앞섬 %)
 * @param projectedKillTimeMs      현재 페이스 유지 시 예상 클리어 시간 (밀리초)
 * @param referenceKillTimeMs      레퍼런스 프로필의 총 전투 시간 (밀리초)
 */
public record PaceComparison(
        String profileLabel,
        long expectedCumulativeDamage,
        long actualCumulativeDamage,
        long deltaDamage,
        double deltaPercent,
        long projectedKillTimeMs,
        long referenceKillTimeMs
) {
}
