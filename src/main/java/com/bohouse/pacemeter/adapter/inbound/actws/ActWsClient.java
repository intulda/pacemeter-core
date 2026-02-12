package com.bohouse.pacemeter.adapter.inbound.actws;

import com.bohouse.pacemeter.application.ActIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

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
        logger.info("[ACT] connect() invoked");
        var client = new StandardWebSocketClient();

        CompletableFuture<WebSocketSession> fut =
                client.execute(new TextWebSocketHandler() {

                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        session.sendMessage(new TextMessage("""
                            {"type":"subscribe","events":["LogLine"]}
                        """.trim()));
                        session.sendMessage(new TextMessage("""
                            {"call":"subscribe","events":["LogLine"]}
                        """.trim()));

                        logger.info("[ACT] subscribed(LogLine)");
                    }

                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        logger.info("[ACT] TEXT(len={}): {}", message.getPayload().length(), message.getPayload());
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