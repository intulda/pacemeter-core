package com.bohouse.pacemeter.application.port.outbound;

import com.bohouse.pacemeter.core.estimator.PaceProfile;

import java.util.Optional;

/**
 * 아웃바운드 포트: 페이스 프로필을 외부에서 가져오는 통로.
 *
 * "페이스 프로필"이란 "잘하는 파티의 시간대별 누적 데미지 기준선"이다.
 * 예를 들어 "절 미래지 상위 10% 파티"의 프로필을 로딩하면,
 * 우리 파티가 그 기준 대비 앞서는지 뒤처지는지 비교할 수 있다.
 *
 * 어댑터 계층에서 이 인터페이스를 구현한다.
 * 예: JSON 파일에서 프로필 로딩, 또는 FF Logs API에서 데이터 가져오기
 */
public interface PaceProfileProvider {

    /**
     * 보스/콘텐츠 이름에 맞는 페이스 프로필을 찾는다.
     *
     * @param fightName      ACT zone name (로그/레이블용)
     * @param actTerritoryId ACT ZoneChanged의 territory ID (fflogs-zones.json 조회 키)
     * @return 해당 프로필. 없으면 Optional.empty()
     */
    Optional<PaceProfile> findProfile(String fightName, int actTerritoryId);

    /**
     * 보스/콘텐츠 이름과 플레이어 직업에 맞는 페이스 프로필을 찾는다.
     *
     * @param fightName      ACT zone name (로그/레이블용)
     * @param actTerritoryId ACT ZoneChanged의 territory ID (fflogs-zones.json 조회 키)
     * @param playerJobId    플레이어 직업 ID (0이면 전체 직업 랭킹)
     * @return 해당 프로필. 없으면 Optional.empty()
     */
    default Optional<PaceProfile> findProfile(String fightName, int actTerritoryId, int playerJobId) {
        // 기본 구현: jobId 무시하고 기존 메서드 호출
        return findProfile(fightName, actTerritoryId);
    }

    /**
     * 개인 직업별 TOP의 개인 DPS 타임라인 프로필을 찾는다.
     * 파티 전체가 아닌, 특정 직업 TOP 플레이어의 개인 데미지 타임라인을 반환한다.
     *
     * @param fightName      ACT zone name (로그/레이블용)
     * @param actTerritoryId ACT ZoneChanged의 territory ID
     * @param playerJobId    플레이어 직업 ID (0이면 프로필 없음)
     * @return 개인 타임라인 프로필. 없으면 Optional.empty()
     */
    default Optional<PaceProfile> findIndividualProfile(String fightName, int actTerritoryId, int playerJobId) {
        // 기본 구현: 개인 프로필을 지원하지 않으면 빈 Optional 반환
        return Optional.empty();
    }
}
