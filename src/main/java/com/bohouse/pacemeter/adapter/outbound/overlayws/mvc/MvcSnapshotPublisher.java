package com.bohouse.pacemeter.adapter.outbound.overlayws.mvc;

import com.bohouse.pacemeter.application.port.outbound.SnapshotPublisher;
import com.bohouse.pacemeter.core.snapshot.OverlaySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MvcSnapshotPublisher implements SnapshotPublisher {

    private static final Logger logger = LoggerFactory.getLogger(MvcSnapshotPublisher.class);

    private final OverlayWsHandler ws;
    private final ObjectMapper om;

    public MvcSnapshotPublisher(OverlayWsHandler ws, ObjectMapper om) {
        this.ws = ws;
        this.om = om;
    }

    @Override
    public void publish(OverlaySnapshot snapshot) {
        if (ws.sessionCount() == 0) {
            logger.debug("[Overlay] no clients connected, skipping publish");
            return;
        }

        try {
            var payload = new Envelope("overlay_tick", snapshot);
            String json = om.writeValueAsString(payload);
            ws.broadcast(json);
            logger.info("[Overlay] published snapshot: elapsed={}ms actors={} partyDps={}",
                    snapshot.elapsedMs(), snapshot.actors().size(), (long) snapshot.partyDps());
        } catch (Exception e) {
            logger.error("[Overlay] publish failed", e);
        }
    }

    private record Envelope(String type, OverlaySnapshot snapshot) {}
}
