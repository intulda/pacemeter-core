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
     * @param fightName 보스 이름 (FightStart 이벤트에서 가져옴)
     * @return 해당 프로필. 없으면 Optional.empty()
     */
    Optional<PaceProfile> findProfile(String fightName);
}
