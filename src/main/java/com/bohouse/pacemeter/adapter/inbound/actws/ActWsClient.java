package com.bohouse.pacemeter.adapter.inbound.actws;

import com.bohouse.pacemeter.application.ActIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ActWsClient {

    Logger logger = LoggerFactory.getLogger(ActWsClient.class);

    private final ActLineParser parser;
    private final ActIngestionService ingestion;

    public ActWsClient(ActLineParser parser, ActIngestionService ingestion) {
        this.parser = parser;
        this.ingestion = ingestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        var client = new StandardWebSocketClient();

        client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                logger.info("ACT RAW >>> {}", message.getPayload());
                for (String line : payload.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    ParsedLine parsed = parser.parse(line);
                    if (parsed != null) ingestion.onParsed(parsed);
                }
            }
        }, "ws://127.0.0.1:10501/ws");
    }
}