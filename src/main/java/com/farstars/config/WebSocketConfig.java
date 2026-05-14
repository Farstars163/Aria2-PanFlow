package com.farstars.config;

import com.farstars.handler.ProgressWebSocket;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Component
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ProgressWebSocket progressWebSocket;
    public WebSocketConfig(ProgressWebSocket progressWebSocket) {
        this.progressWebSocket = progressWebSocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(progressWebSocket, "/ws/progress").setAllowedOrigins("*");
    }
}
