package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.application.port.inbound.CombatEventPort;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.engine.EngineResult;
import com.bohouse.pacemeter.core.estimator.PaceProfile;
import com.bohouse.pacemeter.core.event.CombatEvent;
import com.bohouse.pacemeter.core.model.ActorId;

/**
 * 애플리케이션 서비스: 코어 엔진과 외부 어댑터를 연결하는 중간 다리 역할.
 *
 * 이 클래스가 하는 일:
 *   1. 전투 시작 시 → 페이스 프로필을 찾아서 엔진에 설정
 *   2. 이벤트를 받으면 → 코어 엔진에 전달하여 처리
 *   3. 스냅샷이 나오면 → SnapshotPublisher를 통해 오버레이에 전송
 *
 * 헥사고날 아키텍처에서의 위치:
 *   어댑터(ACT WebSocket) → [인바운드 포트] → CombatService → CombatEngine(코어)
 *                                                    ↓
 *                              오버레이(Electron) ← [아웃바운드 포트] ← 스냅샷
 *
 * 주의: 이 클래스에는 Spring 어노테이션(@Service 등)을 붙이지 않는다.
 * 어댑터 계층의 Spring @Configuration에서 빈으로 등록한다.
 * 이렇게 하면 코어/애플리케이션 계층이 Spring에 의존하지 않게 된다.
 */
public class CombatService implements CombatEventPort {

    private final CombatEngine engine;
    private final SnapshotPublisher snapshotPublisher;
    private final PaceProfileProvider paceProfileProvider;

    public CombatService(
            CombatEngine engine,
            SnapshotPublisher snapshotPublisher,
            PaceProfileProvider paceProfileProvider
    ) {
        this.engine = engine;
        this.snapshotPublisher = snapshotPublisher;
        this.paceProfileProvider = paceProfileProvider;
    }

    @Override
    public EngineResult onEvent(CombatEvent event) {
        // 전투가 시작되면, 해당 보스에 맞는 페이스 프로필을 찾아서 엔진에 세팅
        if (event instanceof CombatEvent.FightStart fightStart) {
            // 파티 전체 페이스 프로필 (직업 무관, 파티 타임라인)
            PaceProfile partyProfile = paceProfileProvider.findProfile(
                    fightStart.fightName(),
                    fightStart.zoneId(),
                    0  // 전체 직업
            ).orElse(PaceProfile.NONE);

            // 개인 직업별 페이스 프로필 (개인 타임라인)
            PaceProfile individualProfile = paceProfileProvider.findIndividualProfile(
                    fightStart.fightName(),
                    fightStart.zoneId(),
                    fightStart.playerJobId()  // 현재 플레이어 직업
            ).orElse(PaceProfile.NONE);

            engine.setProfiles(partyProfile, individualProfile);
        }

        // 코어 엔진에 이벤트 전달 → 상태 업데이트 + (틱이면) 스냅샷 생성
        EngineResult result = engine.process(event);

        // 스냅샷이 생성되었으면 오버레이에 전송
        result.snapshot().ifPresent(snapshotPublisher::publish);

        return result;
    }

    /**
     * 현재 플레이어 ID를 설정한다.
     * ActIngestionService가 ChangePrimaryPlayer를 받았을 때 호출한다.
     */
    public void setCurrentPlayerId(ActorId playerId) {
        engine.setCurrentPlayerId(playerId);
    }

    /**
     * 특정 액터의 직업 ID를 설정한다.
     * ActIngestionService가 CombatantAdded를 받았을 때 호출한다.
     */
    public void setJobId(ActorId actorId, int jobId) {
        engine.setJobId(actorId, jobId);
    }

    /**
     * 펫/소환수의 주인을 설정한다.
     * ActIngestionService가 CombatantAdded를 받았을 때 호출한다.
     */
    public void setOwner(ActorId petId, ActorId ownerId) {
        engine.setOwner(petId, ownerId);
    }
}
