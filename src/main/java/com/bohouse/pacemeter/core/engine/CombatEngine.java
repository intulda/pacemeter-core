package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.core.estimator.OnlineEstimator;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;
import com.bohouse.pacemeter.core.model.CombatState;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import com.bohouse.pacemeter.core.snapshot.SnapshotAggregator;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 전투 엔진 - 이 프로젝트의 핵심 처리기.
 *
 * 하는 일:
 *   1. 이벤트를 받는다 (예: "전사가 12000 데미지를 줬다")
 *   2. CombatState에 반영한다 (상태 업데이트)
 *   3. Tick/FightEnd이면 스냅샷을 만들어서 반환한다
 *
 * 데이터 흐름:
 *   CombatEvent → CombatState.reduce() → (틱이면) SnapshotAggregator → OverlaySnapshot
 *
 * 사용 예시:
 * <pre>
 *   CombatEngine engine = new CombatEngine(paceProfile);
 *   for (CombatEvent event : events) {
 *       EngineResult result = engine.process(event);
 *       result.snapshot().ifPresent(this::publish);  // 스냅샷이 있으면 오버레이에 전송
 *   }
 * </pre>
 *
 * 주의: 멀티스레드 환경에서 안전하지 않다. 반드시 한 스레드에서만 호출해야 한다.
 * 여러 스레드에서 이벤트를 보내려면 어댑터 계층에서 직렬화(순서 보장)해야 한다.
 */
public final class CombatEngine {

    private final CombatState state;
    private final SnapshotAggregator aggregator;
    private PaceProfile partyProfile;        // 파티 전체 vs TOP 파티
    private PaceProfile individualProfile;   // 개인 vs 개인 직업 TOP
    private ActorId currentPlayerId;         // 현재 플레이어 ID
    private final Map<ActorId, Integer> jobIdMap;  // ActorId → JobID 매핑
    private Optional<EnrageTimeProvider.EnrageInfo> enrageInfo;

    /** 페이스 프로필 없이 엔진 생성 */
    public CombatEngine() {
        this(PaceProfile.NONE, PaceProfile.NONE);
    }

    /** 파티 페이스 프로필만 지정하여 엔진 생성 (하위 호환용) */
    public CombatEngine(PaceProfile partyProfile) {
        this(partyProfile, PaceProfile.NONE);
    }

    /** 파티 및 개인 페이스 프로필을 지정하여 엔진 생성 */
    public CombatEngine(PaceProfile partyProfile, PaceProfile individualProfile) {
        this.state = new CombatState();
        this.aggregator = new SnapshotAggregator(new OnlineEstimator());
        this.partyProfile = partyProfile;
        this.individualProfile = individualProfile;
        this.jobIdMap = new HashMap<>();
        this.currentPlayerId = null;
        this.enrageInfo = Optional.empty();
    }

    /**
     * 이벤트 하나를 처리한다.
     *
     * @param event 처리할 전투 이벤트 (null 불가)
     * @return 처리 결과. 스냅샷이 포함될 수도 있고 비어있을 수도 있다.
     */
    public EngineResult process(CombatEvent event) {
        boolean shouldSnapshot = state.reduce(event);

        if (shouldSnapshot) {
            return EngineResult.withSnapshot(snapshotCurrent(event instanceof CombatEvent.FightEnd));
        }

        return EngineResult.empty();
    }

    public OverlaySnapshot snapshotCurrent(boolean isFinal) {
        return aggregator.aggregate(
                state, partyProfile, individualProfile, currentPlayerId, isFinal, jobIdMap, enrageInfo);
    }

    /**
     * 페이스 프로필을 교체한다.
     * 다음 스냅샷부터 새 프로필이 적용된다.
     */
    public void setProfiles(PaceProfile partyProfile, PaceProfile individualProfile) {
        this.partyProfile = (partyProfile != null) ? partyProfile : PaceProfile.NONE;
        this.individualProfile = (individualProfile != null) ? individualProfile : PaceProfile.NONE;
    }

    /**
     * 현재 플레이어 ID를 설정한다.
     * ActIngestionService가 ChangePrimaryPlayer를 받았을 때 호출한다.
     */
    public void setCurrentPlayerId(ActorId playerId) {
        this.currentPlayerId = playerId;
    }

    /**
     * 특정 액터의 직업 ID를 설정한다.
     * ActIngestionService가 CombatantAdded를 받았을 때 호출한다.
     */
    public void setJobId(ActorId actorId, int jobId) {
        jobIdMap.put(actorId, jobId);
    }

    /**
     * 펫/소환수의 주인을 설정한다.
     * ActIngestionService가 CombatantAdded를 받았을 때 호출한다.
     */
    public void setOwner(ActorId petId, ActorId ownerId) {
        state.setOwner(petId, ownerId);
    }

    /** 전투 간에 남아선 안 되는 액터 메타데이터를 초기화한다. */
    public void clearCombatantContext() {
        state.clearOwners();
        jobIdMap.clear();
        enrageInfo = Optional.empty();
    }

    public void setEnrageInfo(Optional<EnrageTimeProvider.EnrageInfo> enrageInfo) {
        this.enrageInfo = enrageInfo != null ? enrageInfo : Optional.empty();
    }

    /** 현재 전투 상태를 반환한다 (테스트/디버깅용). */
    public CombatState currentState() {
        return state;
    }

    public ActorId currentPlayerId() {
        return currentPlayerId;
    }

    public Map<ActorId, Integer> jobIdMap() {
        return Map.copyOf(jobIdMap);
    }
}
