package com.bohouse.pacemeter.application.port.outbound;

import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;

/**
 * 아웃바운드 포트: 스냅샷을 오버레이 클라이언트에게 보내는 출구.
 *
 * 헥사고날 아키텍처에서 "아웃바운드 포트"란,
 * 내부(코어 엔진)의 결과를 외부 세계로 내보내는 통로이다.
 *
 * 실제 사용 흐름:
 *   엔진이 스냅샷 생성 → 이 포트를 통해 전송 → 어댑터가 WebSocket으로 Electron 오버레이에 전달
 *
 * 어댑터 계층에서 이 인터페이스를 구현한다.
 * 예: WebSocketSnapshotPublisher 클래스가 실제 WebSocket 전송을 담당
 */
public interface SnapshotPublisher {

    /**
     * 스냅샷을 연결된 모든 오버레이 클라이언트에게 전송한다.
     *
     * @param snapshot 전송할 오버레이 스냅샷
     */
    void publish(OverlaySnapshot snapshot);
}
