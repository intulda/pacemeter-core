package com.bohouse.pacemeter.core.engine;

import com.bohouse.pacemeter.core.estimator.OnlineEstimator;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.CombatState;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import com.bohouse.pacemeter.core.snapshot.SnapshotAggregator;

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
    private PaceProfile paceProfile;

    /** 페이스 프로필 없이 엔진 생성 */
    public CombatEngine() {
        this(PaceProfile.NONE);
    }

    /** 페이스 프로필을 지정하여 엔진 생성 */
    public CombatEngine(PaceProfile paceProfile) {
        this.state = new CombatState();
        this.aggregator = new SnapshotAggregator(new OnlineEstimator());
        this.paceProfile = paceProfile;
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
            boolean isFinal = event instanceof CombatEvent.FightEnd;
            OverlaySnapshot snapshot = aggregator.aggregate(state, paceProfile, isFinal);
            return EngineResult.withSnapshot(snapshot);
        }

        return EngineResult.empty();
    }

    /**
     * 페이스 프로필을 교체한다.
     * 예: 사용자가 "상위 10% 페이스"에서 "상위 1% 페이스"로 변경했을 때.
     * 다음 스냅샷부터 새 프로필이 적용된다.
     */
    public void setPaceProfile(PaceProfile profile) {
        this.paceProfile = (profile != null) ? profile : PaceProfile.NONE;
    }

    /** 현재 전투 상태를 반환한다 (테스트/디버깅용). */
    public CombatState currentState() {
        return state;
    }
}
