package com.bohouse.pacemeter.adapter.outbound.overlayws.mvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OverlayWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(OverlayWsHandler.class);
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("[OverlayWS] client connected: {} (total: {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("[OverlayWS] client disconnected: {} status={} (total: {})", session.getId(), status, sessions.size());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void broadcast(String json) {
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) s.sendMessage(msg);
            } catch (Exception e) {
                sessions.remove(s);
                try { s.close(); } catch (Exception ignore) {}
            }
        }
    }
}
