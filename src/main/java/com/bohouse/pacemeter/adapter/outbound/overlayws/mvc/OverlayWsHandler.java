package com.bohouse.pacemeter.adapter.outbound.overlayws.mvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OverlayWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(OverlayWsHandler.class);
    private static final String DEFAULT_SESSION_ID = "global";

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<WebSocketSession>> sessionsByRelayId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        String relaySessionId = resolveRelaySessionId(session);
        session.getAttributes().put("relaySessionId", relaySessionId);
        sessionsByRelayId.computeIfAbsent(relaySessionId, key -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("[OverlayWS] client connected: {} relaySessionId={} (total: {})",
                session.getId(), relaySessionId, sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        String relaySessionId = (String) session.getAttributes().getOrDefault("relaySessionId", DEFAULT_SESSION_ID);
        Set<WebSocketSession> scopedSessions = sessionsByRelayId.get(relaySessionId);
        if (scopedSessions != null) {
            scopedSessions.remove(session);
            if (scopedSessions.isEmpty()) {
                sessionsByRelayId.remove(relaySessionId);
            }
        }
        log.info("[OverlayWS] client disconnected: {} relaySessionId={} status={} (total: {})",
                session.getId(), relaySessionId, status, sessions.size());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void broadcast(String json) {
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            sendMessage(session, msg);
        }
    }

    public void broadcastToSession(String relaySessionId, String json) {
        TextMessage msg = new TextMessage(json);
        Set<WebSocketSession> scopedSessions = sessionsByRelayId.getOrDefault(relaySessionId, Set.of());
        for (WebSocketSession s : scopedSessions) {
            sendMessage(s, msg);
        }
    }

    private void sendMessage(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (Exception e) {
            sessions.remove(session);
            String relaySessionId = (String) session.getAttributes().getOrDefault("relaySessionId", DEFAULT_SESSION_ID);
            Set<WebSocketSession> scopedSessions = sessionsByRelayId.get(relaySessionId);
            if (scopedSessions != null) {
                scopedSessions.remove(session);
                if (scopedSessions.isEmpty()) {
                    sessionsByRelayId.remove(relaySessionId);
                }
            }
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
    }

    private String resolveRelaySessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return DEFAULT_SESSION_ID;
        }

        for (String pair : uri.getQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && "sessionId".equals(parts[0]) && !parts[1].isBlank()) {
                return parts[1];
            }
        }
        return DEFAULT_SESSION_ID;
    }
}
