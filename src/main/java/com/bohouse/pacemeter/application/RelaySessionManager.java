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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RelaySessionManager {
    private static final Logger logger = LoggerFactory.getLogger(RelaySessionManager.class);
    private static final long RAW_LINE_TYPE_LOG_EVERY = 50L;
    private static final int MAX_PARSE_FAILURE_SAMPLES_PER_TYPE = 3;
    private static final int MAX_PARSE_FAILURE_SAMPLE_LENGTH = 220;

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
        private final Map<Integer, Long> rawLineTypeCounts = new ConcurrentHashMap<>();
        private final Map<Integer, Long> rawLineParseFailureCountsByType = new ConcurrentHashMap<>();
        private final Map<Integer, ArrayDeque<String>> rawLineParseFailureSamplesByType = new HashMap<>();
        private long rawLineCount;
        private long rawLineParseFailureCount;
        private long nextRawLineTypeLogAt = RAW_LINE_TYPE_LOG_EVERY;

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
                    int rawTypeCode = extractRawLineTypeCode(event.rawLine());
                    rawLineCount++;
                    rawLineTypeCounts.merge(rawTypeCode, 1L, Long::sum);
                    ParsedLine parsed = parser.parse(event.rawLine());
                    if (parsed != null) {
                        ingestion.onParsed(parsed);
                    } else {
                        rawLineParseFailureCount++;
                        noteParseFailure(rawTypeCode, event.rawLine());
                    }
                    logRawLineTypeSummaryIfDue();
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

        private void logRawLineTypeSummaryIfDue() {
            if (rawLineCount < nextRawLineTypeLogAt) {
                return;
            }
            String topTypes = rawLineTypeCounts.entrySet().stream()
                    .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .limit(12)
                    .map(entry -> entry.getKey() + ":" + entry.getValue())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
            logger.info(
                    "[Relay] session={} rawLineTypes total={} parseFailed={} top={}",
                    sessionId,
                    rawLineCount,
                    rawLineParseFailureCount,
                    topTypes
            );
            if (rawLineParseFailureCount > 0) {
                String failTop = rawLineParseFailureCountsByType.entrySet().stream()
                        .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                        .limit(6)
                        .map(entry -> entry.getKey() + ":" + entry.getValue())
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("none");
                logger.info(
                        "[Relay] session={} parseFailByType total={} top={}",
                        sessionId,
                        rawLineParseFailureCount,
                        failTop
                );
                rawLineParseFailureCountsByType.entrySet().stream()
                        .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                        .limit(3)
                        .forEach(entry -> {
                            ArrayDeque<String> samples = rawLineParseFailureSamplesByType.get(entry.getKey());
                            if (samples == null || samples.isEmpty()) {
                                return;
                            }
                            String joined = String.join(" || ", samples);
                            logger.info(
                                    "[Relay] session={} parseFailSamples type={} samples={}",
                                    sessionId,
                                    entry.getKey(),
                                    joined
                            );
                        });
            }
            nextRawLineTypeLogAt += RAW_LINE_TYPE_LOG_EVERY;
        }

        private void noteParseFailure(int rawTypeCode, String rawLine) {
            rawLineParseFailureCountsByType.merge(rawTypeCode, 1L, Long::sum);
            ArrayDeque<String> samples = rawLineParseFailureSamplesByType.computeIfAbsent(
                    rawTypeCode,
                    ignored -> new ArrayDeque<>()
            );
            if (samples.size() >= MAX_PARSE_FAILURE_SAMPLES_PER_TYPE) {
                return;
            }
            String normalized = rawLine.replace("\n", "\\n").replace("\r", "\\r");
            if (normalized.length() > MAX_PARSE_FAILURE_SAMPLE_LENGTH) {
                normalized = normalized.substring(0, MAX_PARSE_FAILURE_SAMPLE_LENGTH) + "...";
            }
            samples.addLast(normalized);
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

    private static int extractRawLineTypeCode(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return -1;
        }
        int separatorIndex = rawLine.indexOf('|');
        String typeToken = separatorIndex >= 0 ? rawLine.substring(0, separatorIndex) : rawLine;
        try {
            return Integer.parseInt(typeToken);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
