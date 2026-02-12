package com.bohouse.pacemeter.core.snapshot;

import com.bohouse.pacemeter.core.model.CombatState;

import java.util.List;

/**
 * 매 틱마다 오버레이 화면에 보내는 "전투 현황 스냅샷".
 * 코어 엔진의 최종 출력물이다.
 *
 * 오버레이는 이 데이터를 받아서 바로 렌더링한다.
 * 클라이언트(프론트엔드)에서 추가 계산할 필요 없이, 여기에 모든 정보가 들어있다.
 *
 * @param fightName         보스/콘텐츠 이름 (예: "절 미래지")
 * @param phase             현재 전투 단계 (ACTIVE=진행중, ENDED=종료)
 * @param elapsedMs         전투 시작 이후 경과 시간 (밀리초)
 * @param elapsedFormatted  사람이 읽기 좋은 경과 시간 (예: "2:35")
 * @param totalPartyDamage  파티 전체 누적 데미지 합계
 * @param partyDps          파티 전체 DPS (초당 데미지)
 * @param actors            캐릭터별 상세 데이터 목록 (데미지 높은 순으로 정렬됨)
 * @param paceComparison    레퍼런스 페이스와의 비교 결과 (프로필이 없으면 null)
 * @param isFinal           true이면 이 전투의 마지막 스냅샷 (FightEnd 시 생성)
 */
public record OverlaySnapshot(
        String fightName,
        CombatState.Phase phase,
        long elapsedMs,
        String elapsedFormatted,
        long totalPartyDamage,
        double partyDps,
        List<ActorSnapshot> actors,
        PaceComparison paceComparison,
        boolean isFinal
) {
}
