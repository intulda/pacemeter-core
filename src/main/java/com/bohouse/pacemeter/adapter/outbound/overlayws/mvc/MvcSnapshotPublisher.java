package com.bohouse.pacemeter.adapter.outbound.overlayws.mvc;

import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MvcSnapshotPublisher implements SnapshotPublisher {

    private final OverlayWsHandler ws;
    private final ObjectMapper om;

    public MvcSnapshotPublisher(OverlayWsHandler ws, ObjectMapper om) {
        this.ws = ws;
        this.om = om;
    }

    @Override
    public void publish(OverlaySnapshot snapshot) {
        if (ws.sessionCount() == 0) return;

        try {
            // 프론트에서 type으로 라우팅 가능
            var payload = new Envelope("overlay_tick", snapshot);
            ws.broadcast(om.writeValueAsString(payload));
        } catch (Exception e) {
            // TODO: log
        }
    }

    private record Envelope(String type, OverlaySnapshot snapshot) {}
}
