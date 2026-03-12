package com.bohouse.pacemeter.adapter.inbound.relay;

import com.bohouse.pacemeter.application.RelaySessionManager;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/relay")
public class RelayController {

    private final RelaySessionManager relaySessionManager;

    public RelayController(RelaySessionManager relaySessionManager) {
        this.relaySessionManager = relaySessionManager;
    }

    @PostMapping("/{sessionId}/events")
    public Map<String, Object> ingestEvents(
            @PathVariable String sessionId,
            @RequestBody List<RelaySessionManager.RelayEnvelope> events
    ) {
        relaySessionManager.ingest(sessionId, events);
        return Map.of(
                "ok", true,
                "sessionId", sessionId,
                "count", events.size()
        );
    }
}
