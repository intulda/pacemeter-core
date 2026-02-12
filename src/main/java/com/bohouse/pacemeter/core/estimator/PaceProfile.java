package com.bohouse.pacemeter.core.estimator;

/**
 * 레퍼런스 페이스 프로필: "잘하는 파티라면 이 시점에 총 데미지가 이 정도여야 한다"는 기준 데이터.
 *
 * FF Logs에서 가져온 상위 파티의 누적 데미지 곡선을 기반으로 한다.
 * 코어 엔진은 이 인터페이스만 바라보고, 실제 데이터는 어댑터 계층에서 JSON 파일 등으로 로딩한다.
 *
 * 규약:
 *   - expectedCumulativeDamage(0)은 항상 0을 반환해야 한다
 *   - 반환값은 시간이 갈수록 같거나 커져야 한다 (단조 증가)
 *   - 프로필 범위를 넘는 시간에 대해서는 마지막 구간을 기준으로 선형 외삽한다
 */
public interface PaceProfile {

    /**
     * 전투 시작 후 elapsedMs 시점에서 기대되는 파티 누적 데미지.
     *
     * 예: 상위 10% 파티가 60초 시점에 보통 120만 데미지를 줬다면,
     *     expectedCumulativeDamage(60000) = 1200000
     *
     * @param elapsedMs 전투 시작 기준 경과 시간 (밀리초)
     * @return 해당 시점의 기대 누적 데미지
     */
    long expectedCumulativeDamage(long elapsedMs);

    /**
     * 프로필의 이름. 오버레이에 표시된다.
     * 예: "절미래지_상위10%", "절알렉_상위1%"
     */
    String label();

    /**
     * 레퍼런스 전투의 총 소요 시간 (밀리초).
     * 예: 12분 전투면 720000
     */
    long totalDurationMs();

    /**
     * "프로필 없음"을 나타내는 null 객체.
     * 프로필이 로딩되지 않았을 때 null 대신 이것을 사용하면
     * null 체크를 안 해도 되어서 코드가 깔끔해진다.
     */
    PaceProfile NONE = new PaceProfile() {
        @Override public long expectedCumulativeDamage(long elapsedMs) { return 0; }
        @Override public String label() { return "none"; }
        @Override public long totalDurationMs() { return 0; }
    };
}
