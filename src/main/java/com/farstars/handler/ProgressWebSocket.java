package com.farstars.handler;

import com.farstars.service.Aria2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
@Component
public class ProgressWebSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProgressWebSocket.class);

    private Aria2Client aria2Client;
    private ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public ProgressWebSocket(Aria2Client aria2Client, ObjectMapper objectMapper) {
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 连接建立: {}, 当前在线: {}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket 连接断开: {}, 当前在线: {}", session.getId(), sessions.size());
    }

    /**
     * 轮询
     */
    @Scheduled(fixedRate = 1000)
    public void pushSocket(){
        if(sessions.isEmpty()){
            return;
        }
        try{
            var active = aria2Client.tellActive();
            var waiting = aria2Client.tellWaiting();
            var stopped = aria2Client.tellStopped();
            var globalStat = aria2Client.getGlobalStat();

            String totalSpeed = "0";
            if(globalStat != null && globalStat.has("result")){
                totalSpeed = globalStat.get("result").get("downloadSpeed").asText();
            }
            //构造前端接收数据
            Map<String,Object> resultData = Map.of(
                    "active",active,
                    "waiting",waiting,
                    "complete",stopped,
                    "totalSpeed",totalSpeed
            );
            String result = objectMapper.writeValueAsString(Map.of("result",resultData));
            TextMessage message = new TextMessage(result);

            //广播
            for(WebSocketSession session : sessions){
                if(session.isOpen()){
                    session.sendMessage(message);
                }
            }

        } catch (IOException e) {
            log.error("WebSocket 推送进度失败: {}", e.getMessage());
        }
    }

}
