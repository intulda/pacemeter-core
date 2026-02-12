package com.bohouse.pacemeter.application.port.inbound;

import com.bohouse.pacemeter.core.engine.EngineResult;
import com.bohouse.pacemeter.core.event.CombatEvent;

/**
 * 인바운드 포트: 외부에서 전투 이벤트를 엔진에 전달하는 입구.
 *
 * 헥사고날 아키텍처에서 "인바운드 포트"란,
 * 외부 세계(어댑터)가 내부(코어 엔진)에 데이터를 보내는 통로이다.
 *
 * 실제 사용 흐름:
 *   ACT WebSocket → 어댑터가 로그를 CombatEvent로 변환 → 이 포트를 통해 엔진에 전달
 *
 * 어댑터가 해야 할 일:
 *   - ACT 로그 라인을 CombatEvent 객체로 변환
 *   - 시스템 시계 기준 타임스탬프를 전투 시작 기준(fight-relative)으로 변환
 *   - 약 250ms마다 Tick 이벤트 주입
 *   - 이벤트를 타임스탬프 순서대로 전달
 */
public interface CombatEventPort {

    /**
     * 전투 이벤트 하나를 받아서 엔진으로 처리한다.
     *
     * @param event 변환된 전투 이벤트
     * @return 엔진 처리 결과 (스냅샷이 포함되어 있을 수 있음)
     */
    EngineResult onEvent(CombatEvent event);
}
