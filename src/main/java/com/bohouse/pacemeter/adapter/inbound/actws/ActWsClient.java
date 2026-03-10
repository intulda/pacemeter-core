package com.bohouse.pacemeter.adapter.inbound.actws;

import com.bohouse.pacemeter.application.ActIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ActWsClient {

    private static final Logger logger = LoggerFactory.getLogger(ActWsClient.class);
    private static final String ACT_WS_URL = "ws://127.0.0.1:10501/ws";
    private static final int MAX_RETRY_DELAY_MS = 30_000; // 최대 30초
    private static final int INITIAL_RETRY_DELAY_MS = 1_000; // 첫 재시도 1초
    private static final int MAX_RETRY_COUNT = 100; // 최대 100회 재시도 (약 50분)

    private final ObjectMapper objectMapper;
    private final ActLineParser parser;
    private final ActIngestionService ingestion;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private volatile boolean shouldReconnect = true;

    public ActWsClient(ActLineParser parser, ActIngestionService ingestion, ObjectMapper objectMapper) {
        this.parser = parser;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startConnecting() {
        logger.info("[ACT] starting connection manager");
        shouldReconnect = true;
        connect();
    }

    private void connect() {
        if (!shouldReconnect) {
            logger.info("[ACT] reconnect disabled, stopping");
            return;
        }

        logger.info("[ACT] attempting connection to {}", ACT_WS_URL);
        var client = new StandardWebSocketClient();

        CompletableFuture<WebSocketSession> fut =
                client.execute(new TextWebSocketHandler() {

                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        // 연결 성공 → 재시도 카운터 리셋
                        retryCount.set(0);
                        logger.info("[ACT] connected successfully");

                        // 구독: 현재 전투 상태 + 플레이어 정보 + 실시간 로그 + 존 변경
                        session.sendMessage(new TextMessage(
                                "{\"call\":\"subscribe\",\"events\":[\"CombatData\",\"ChangePrimaryPlayer\",\"LogLine\",\"ChangeZone\"]}"));
                        logger.info("[ACT] subscribed(CombatData, ChangePrimaryPlayer, LogLine, ChangeZone)");
                    }

                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        String payload = message.getPayload();
                        try {
                            JsonNode root = objectMapper.readTree(payload);
                            String type = root.path("type").asText("");

                            // ChangePrimaryPlayer 이벤트: 구독 즉시 + 변경 시 발생
                            if ("ChangePrimaryPlayer".equals(type)) {
                                long charId = root.path("charID").asLong(0);
                                String charName = root.path("charName").asText("");
                                logger.info("[ACT] ChangePrimaryPlayer: name={} id={}", charName, Long.toHexString(charId));
                                if (charId != 0 && !charName.isEmpty()) {
                                    ingestion.onParsed(new PrimaryPlayerChanged(
                                            java.time.Instant.now(), charId, charName));
                                }
                                return;
                            }

                            // ChangeZone 이벤트: 구독 즉시 현재 존 + 이후 존 변경 시 발생
                            if ("ChangeZone".equals(type)) {
                                int zoneId = root.path("zoneID").asInt(0);
                                String zoneName = root.path("zoneName").asText("");
                                if (zoneId > 0) {
                                    ingestion.onParsed(new ZoneChanged(Instant.now(), zoneId, zoneName));
                                    logger.info("[ACT] ChangeZone: id={} name={}", zoneId, zoneName);
                                }
                                return;
                            }

                            // CombatData 이벤트: 연결 시점의 전투 상태 + 주기적 업데이트
                            if ("CombatData".equals(type)) {
                                handleCombatData(root);
                                return;
                            }

                            if (!"LogLine".equals(type)) return;

                            JsonNode rawLineNode = root.path("rawLine");
                            if (rawLineNode.isMissingNode() || !rawLineNode.isTextual()) {
                                logger.warn("[ACT] LogLine but no rawLine field");
                                return;
                            }

                            String rawLine = rawLineNode.asText();
                            ParsedLine parsed = parser.parse(rawLine);
                            if (parsed != null) {
                                ingestion.onParsed(parsed);
                            } else {
                                // 파싱 실패한 라인은 디버그 레벨로 로깅 (너무 많이 나올 수 있음)
                                logger.debug("[ACT] failed to parse line: {}", rawLine);
                            }
                        } catch (Exception e) {
                            logger.warn("[ACT] payload parse error: {} | payload: {}", e.getMessage(), payload.substring(0, Math.min(200, payload.length())));
                        }
                    }

                    @Override
                    public void handleTransportError(WebSocketSession session, Throwable exception) {
                        logger.error("[ACT] transport error: {}", exception.getMessage());
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                        logger.warn("[ACT] connection closed: code={} reason={}", status.getCode(), status.getReason());
                        scheduleReconnect();
                    }
                }, ACT_WS_URL);

        fut.whenComplete((session, ex) -> {
            if (ex != null) {
                logger.error("[ACT] connection failed: {}", ex.getMessage());
                scheduleReconnect();
            } else {
                logger.info("[ACT] connection future completed. sessionOpen={}", session.isOpen());
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) {
            logger.info("[ACT] reconnect disabled, not scheduling retry");
            return;
        }

        int retry = retryCount.getAndIncrement();

        // 최대 재시도 횟수 체크
        if (retry >= MAX_RETRY_COUNT) {
            logger.error("[ACT] max retry count ({}) reached, giving up. Please check if ACT is running.", MAX_RETRY_COUNT);
            shouldReconnect = false;
            return;
        }

        // Exponential backoff: 1초, 2초, 4초, 8초, 16초, 30초(최대)
        int delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, retry), MAX_RETRY_DELAY_MS);

        logger.info("[ACT] scheduling reconnect in {}ms (retry #{}/{})", delayMs, retry + 1, MAX_RETRY_COUNT);
        scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
    }

    public void stopReconnecting() {
        logger.info("[ACT] stopping reconnection attempts");
        shouldReconnect = false;
    }

    /**
     * CombatData 이벤트 처리: 현재 전투 중인 파티원 정보 복원
     */
    private void handleCombatData(JsonNode root) {
        try {
            boolean isActive = root.path("isActive").asBoolean(false);

            if (!isActive) {
                logger.debug("[ACT] CombatData: not in combat");
                return;
            }

            JsonNode combatantNode = root.path("Combatant");
            if (!combatantNode.isObject()) {
                logger.warn("[ACT] CombatData: no Combatant object");
                return;
            }

            Instant now = Instant.now();

            // 각 파티원 정보 처리
            combatantNode.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode combatant = entry.getValue();

                // ID 파싱 (hex string → long)
                String idHex = combatant.path("ID").asText("");
                if (idHex.isEmpty()) return;

                long id = 0;
                try {
                    id = Long.parseUnsignedLong(idHex.replace("0x", ""), 16);
                } catch (NumberFormatException e) {
                    logger.warn("[ACT] CombatData: invalid ID for {}: {}", name, idHex);
                    return;
                }

                // Job ID 파싱
                int jobId = combatant.path("Job").asInt(0);

                // CombatantAdded 이벤트 생성 (전투 중인 파티원 등록)
                logger.info("[ACT] CombatData: restoring combatant {}(id={} job={})",
                        name, Long.toHexString(id), Integer.toHexString(jobId));

                ingestion.onParsed(new CombatantAdded(
                        now, id, name, jobId, 0L, "" // ownerId는 0 (펫은 별도 처리)
                ));
            });

            logger.info("[ACT] CombatData: restored {} combatants from ongoing combat",
                    combatantNode.size());

            // 파티 데이터 초기화 완료 알림
            ingestion.onCombatDataReady(combatantNode.size());

        } catch (Exception e) {
            logger.error("[ACT] CombatData parse error: {}", e.getMessage(), e);
        }
    }
}