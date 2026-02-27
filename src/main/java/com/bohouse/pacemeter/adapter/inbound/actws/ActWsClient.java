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

@Component
public class ActWsClient {

    private static final Logger logger = LoggerFactory.getLogger(ActWsClient.class);

    private final ActLineParser parser;
    private final ActIngestionService ingestion;
    private final ObjectMapper objectMapper;

    public ActWsClient(ActLineParser parser, ActIngestionService ingestion, ObjectMapper objectMapper) {
        this.parser = parser;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        logger.info("[ACT] connect() invoked");
        var client = new StandardWebSocketClient();

        CompletableFuture<WebSocketSession> fut =
                client.execute(new TextWebSocketHandler() {

                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
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
                        logger.error("[ACT] transport error", exception);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                        logger.warn("[ACT] closed code={} reason={}", status.getCode(), status.getReason());
                    }
                }, "ws://127.0.0.1:10501/ws");

        fut.whenComplete((session, ex) -> {
            if (ex != null) {
                logger.error("[ACT] connect failed", ex);
                return;
            }
            logger.info("[ACT] connect future completed. sessionOpen={}", session.isOpen());
        });

    }
}