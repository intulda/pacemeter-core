package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.core.estimator.Confidence;
import com.bohouse.pacemeter.core.model.ActorId;

/**
 * 오버레이 스냅샷에 포함되는 캐릭터 한 명의 전투 데이터.
 * JSON으로 변환되어 오버레이 화면에 직접 표시된다.
 *
 * 예시: 전사 캐릭터가 2분간 전투한 경우
 *   - actorId: ActorId(1)
 *   - name: "전사 이름"
 *   - jobId: 0x15 (21 = 전사)
 *   - totalDamage: 480000 (48만)
 *   - dps: 4000.0 (480000 / 120초)
 *   - onlineRdps: 4200.0 (버프 보정 포함 근사값)
 *   - rdpsConfidence: 신뢰도 0.8, 사유: ["전투 60초 미만"]
 *   - damagePercent: 0.25 (파티 데미지의 25%)
 *   - hitCount: 85 (총 85회 공격)
 *   - recentDps: 4500.0 (최근 15초 DPS)
 *   - individualPace: 나 vs 내 직업 TOP 비교 (null이면 비교 없음)
 *
 * @param actorId           캐릭터 고유 ID
 * @param name              캐릭터 표시 이름
 * @param jobId             FFXIV 직업 ID (hex: 0x13=점성술사, 0x26=무도가 등)
 * @param totalDamage       전투 시작부터 지금까지 준 총 데미지
 * @param dps               누적 DPS (총 데미지 / 경과 초)
 * @param onlineRdps        온라인 rDPS 추정치 (근사값, FF Logs rDPS와 다름!)
 * @param rdpsConfidence    rDPS 추정치의 신뢰도 (얼마나 믿을 수 있는지)
 * @param damagePercent     파티 전체 데미지 중 이 캐릭터의 비율 [0.0 ~ 1.0]
 * @param hitCount          총 공격 횟수
 * @param recentDps         최근 슬라이딩 윈도우(15초) 기간의 DPS
 * @param isCurrentPlayer   현재 플레이어 여부
 * @param individualPace    개인 vs 직업 TOP 비교 (확장성: 각 파티원마다 가능)
 * @param isDead            사망 상태 (true면 현재 사망 중)
 */
public record ActorSnapshot(
        ActorId actorId,
        String name,
        int jobId,
        long totalDamage,
        double dps,
        double onlineRdps,
        Confidence rdpsConfidence,
        double damagePercent,
        int hitCount,
        double recentDps,
        boolean isCurrentPlayer,
        PaceComparison individualPace,
        boolean isDead
) {
}
