package com.bohouse.pacemeter.adapter.inbound.relay;

import com.bohouse.pacemeter.application.RelaySessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(RelayController.class);

    private final RelaySessionManager relaySessionManager;

    public RelayController(RelaySessionManager relaySessionManager) {
        this.relaySessionManager = relaySessionManager;
    }

    @PostMapping("/{sessionId}/events")
    public Map<String, Object> ingestEvents(
            @PathVariable String sessionId,
            @RequestBody List<RelaySessionManager.RelayEnvelope> events
    ) {
        long rawLineCount = events.stream()
                .filter(event -> "rawLine".equals(event.type()))
                .count();
        logger.info("[RelayAPI] ingest session={} events={} rawLine={}", sessionId, events.size(), rawLineCount);
        relaySessionManager.ingest(sessionId, events);
        return Map.of(
                "ok", true,
                "sessionId", sessionId,
                "count", events.size()
        );
    }
}
