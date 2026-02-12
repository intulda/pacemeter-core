package com.bohouse.pacemeter.core.estimator;

/**
 * 한 캐릭터에 대한 온라인 rDPS 추정 결과.
 *
 * FF Logs의 정확한 rDPS가 아니라, 실시간으로 계산한 "대략적인" 추정치이다.
 * 주로 페이스 비교용으로 사용하며, 파싱 순위 매기기용이 아니다.
 *
 * @param actorOnlineRdps 추정된 rDPS (초당 데미지, 근사값)
 * @param confidence      이 숫자를 얼마나 믿을 수 있는지 (신뢰도)
 */
public record RdpsEstimate(double actorOnlineRdps, Confidence confidence) {
}
