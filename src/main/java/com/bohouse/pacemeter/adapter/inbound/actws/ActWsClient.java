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

                        // ChangePrimaryPlayer 구독 → 즉시 현재 플레이어 정보 수신
                        session.sendMessage(new TextMessage(
                                "{\"call\":\"subscribe\",\"events\":[\"ChangePrimaryPlayer\",\"LogLine\"]}"));
                        logger.info("[ACT] subscribed(ChangePrimaryPlayer, LogLine)");
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
                            }
                        } catch (Exception e) {
                            logger.warn("[ACT] payload parse error: {}", e.getMessage(), e);
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
        // Exponential backoff: 1초, 2초, 4초, 8초, 16초, 30초(최대)
        int delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, retry), MAX_RETRY_DELAY_MS);

        logger.info("[ACT] scheduling reconnect in {}ms (retry #{})", delayMs, retry + 1);
        scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
    }

    public void stopReconnecting() {
        logger.info("[ACT] stopping reconnection attempts");
        shouldReconnect = false;
    }
}