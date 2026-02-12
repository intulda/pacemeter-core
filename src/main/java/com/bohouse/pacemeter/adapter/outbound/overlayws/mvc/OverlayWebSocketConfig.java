package com.bohouse.pacemeter.adapter.outbound.overlayws.mvc;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class OverlayWebSocketConfig implements WebSocketConfigurer {
    private final OverlayWsHandler handler;

    public OverlayWebSocketConfig(OverlayWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/overlay/ws")
                .setAllowedOrigins("*"); // MVP. 로컬만이면 추후 제한
    }
}
