package com.bohouse.pacemeter.application;

import com.bohouse.pacemeter.adapter.inbound.actws.ActLineParser;
import com.bohouse.pacemeter.adapter.inbound.actws.CombatantAdded;
import com.bohouse.pacemeter.adapter.inbound.actws.ParsedLine;
import com.bohouse.pacemeter.adapter.inbound.actws.PrimaryPlayerChanged;
import com.bohouse.pacemeter.adapter.inbound.actws.ZoneChanged;
import com.bohouse.pacemeter.adapter.outbound.fflogsapi.FflogsZoneLookup;
import com.bohouse.pacemeter.adapter.outbound.overlayws.mvc.OverlayWsHandler;
import com.bohouse.pacemeter.application.port.outbound.EnrageTimeProvider;
import com.bohouse.pacemeter.application.port.outbound.PaceProfileProvider;
import com.bohouse.pacemeter.core.engine.CombatEngine;
import com.bohouse.pacemeter.core.event.CombatEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RelaySessionManager {

    private final Map<String, RelaySession> sessions = new ConcurrentHashMap<>();
    private final PaceProfileProvider paceProfileProvider;
    private final EnrageTimeProvider enrageTimeProvider;
    private final FflogsZoneLookup fflogsZoneLookup;
    private final ActLineParser parser;
    private final OverlayWsHandler overlayWsHandler;
    private final ObjectMapper objectMapper;

    public RelaySessionManager(
            PaceProfileProvider paceProfileProvider,
            EnrageTimeProvider enrageTimeProvider,
            FflogsZoneLookup fflogsZoneLookup,
            ActLineParser parser,
            OverlayWsHandler overlayWsHandler,
            ObjectMapper objectMapper
    ) {
        this.paceProfileProvider = paceProfileProvider;
        this.enrageTimeProvider = enrageTimeProvider;
        this.fflogsZoneLookup = fflogsZoneLookup;
        this.parser = parser;
        this.overlayWsHandler = overlayWsHandler;
        this.objectMapper = objectMapper;
    }

    public void ingest(String sessionId, List<RelayEnvelope> events) {
        RelaySession session = sessions.computeIfAbsent(sessionId, this::createSession);
        session.ingest(events);
    }

    public CombatDebugSnapshot debugSnapshot(String sessionId) {
        return sessions.computeIfAbsent(sessionId, this::createSession).debugSnapshot();
    }

    @Scheduled(fixedRate = 100)
    public void tickSessions() {
        for (RelaySession session : sessions.values()) {
            session.tick();
        }
    }

    private RelaySession createSession(String sessionId) {
        CombatEngine engine = new CombatEngine();
        CombatService combatService = new CombatService(
                engine,
                snapshot -> {
                    try {
                        String json = objectMapper.writeValueAsString(new Envelope("snapshot", snapshot));
                        overlayWsHandler.broadcastToSession(sessionId, json);
                    } catch (Exception ignored) {
                    }
                },
                paceProfileProvider,
                enrageTimeProvider
        );
        ActIngestionService ingestion = new ActIngestionService(combatService, combatService, fflogsZoneLookup);
        return new RelaySession(sessionId, combatService, ingestion);
    }

    private final class RelaySession {
        private final String sessionId;
        private final CombatService combatService;
        private final ActIngestionService ingestion;

        private RelaySession(String sessionId, CombatService combatService, ActIngestionService ingestion) {
            this.sessionId = sessionId;
            this.combatService = combatService;
            this.ingestion = ingestion;
        }

        private synchronized void ingest(List<RelayEnvelope> events) {
            for (RelayEnvelope event : events) {
                apply(event);
            }
        }

        private synchronized CombatDebugSnapshot debugSnapshot() {
            return combatService.debugSnapshot();
        }

        private synchronized void tick() {
            if (!ingestion.isFightStarted()) {
                return;
            }
            long elapsed = ingestion.nowElapsedMs();
            combatService.onEvent(new CombatEvent.Tick(elapsed));
        }

        private void apply(RelayEnvelope event) {
            switch (event.type()) {
                case "rawLine" -> {
                    if (event.rawLine() == null || event.rawLine().isBlank()) {
                        return;
                    }
                    ParsedLine parsed = parser.parse(event.rawLine());
                    if (parsed != null) {
                        ingestion.onParsed(parsed);
                    }
                }
                case "changePrimaryPlayer" -> {
                    if (event.ts() == null || event.playerName() == null || event.playerId() == null) return;
                    ingestion.onParsed(new PrimaryPlayerChanged(
                            Instant.parse(event.ts()),
                            event.playerId(),
                            event.playerName()
                    ));
                }
                case "changeZone" -> {
                    if (event.ts() == null || event.zoneName() == null || event.zoneId() == null) return;
                    ingestion.onParsed(new ZoneChanged(
                            Instant.parse(event.ts()),
                            event.zoneId(),
                            event.zoneName()
                    ));
                }
                case "combatantAdded" -> {
                    if (event.ts() == null || event.actorId() == null || event.name() == null) return;
                    ingestion.onParsed(new CombatantAdded(
                            Instant.parse(event.ts()),
                            event.actorId(),
                            event.name(),
                            valueOrDefault(event.jobId(), 0),
                            0L,
                            valueOrDefault(event.currentHp(), 0L),
                            valueOrDefault(event.maxHp(), 0L),
                            ""
                    ));
                }
                case "combatDataReady" -> {
                    if (event.memberCount() == null) return;
                    ingestion.onCombatDataReady(event.memberCount());
                }
                default -> {
                }
            }
        }
    }

    public record RelayEnvelope(
            String type,
            String ts,
            String rawLine,
            Long playerId,
            String playerName,
            Integer zoneId,
            String zoneName,
            Long actorId,
            String name,
            Integer jobId,
            Long currentHp,
            Long maxHp,
            Integer memberCount
    ) {
    }

    private record Envelope(String type, Object snapshot) {
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static long valueOrDefault(Long value, long defaultValue) {
        return value != null ? value : defaultValue;
    }
}
