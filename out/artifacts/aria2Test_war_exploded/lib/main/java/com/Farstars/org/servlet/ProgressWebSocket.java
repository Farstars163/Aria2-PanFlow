package com.Farstars.org.servlet;

import com.Farstars.org.service.Aria2ClientImpl;
import com.Farstars.org.service.impl.Aria2Client;
import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/ws/progress")
public class ProgressWebSocket {
    protected static Aria2Client Aria2Client =  new Aria2ClientImpl();

    // 保存所有连接的客户端Session
    private static final Set<Session> sessions = new CopyOnWriteArraySet<>();

    // 定时任务线程池，每秒查询一次Aria2进度
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 静态代码块：项目启动时自动开启定时推送
    static {
        scheduler.scheduleAtFixedRate(() -> {
            if (sessions.isEmpty()) return; // 无连接时不查询，节省资源

            try {
                // 调用Aria2查询所有活跃任务
                com.alibaba.fastjson.JSONArray activeTasks = Aria2Client.tellActive();

                //调用Aria2查询所有等待任务

                com.alibaba.fastjson.JSONArray waitingTasks = Aria2Client.tellWaiting();

                JSONObject resultData = new JSONObject();

                resultData.put("active",activeTasks != null ? activeTasks : new com.alibaba.fastjson.JSONArray());

                resultData.put("waiting",waitingTasks != null ? waitingTasks : new com.alibaba.fastjson.JSONArray());

                // 构造前端预期的格式 {"result": [...]}
                JSONObject response = new JSONObject();
                response.put("result", resultData);

                String message = response.toJSONString();

                // 广播给所有在线客户端
                for (Session session : sessions) {
                    if (session.isOpen()) {
                        try {
                            session.getAsyncRemote().sendText(message);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    // 提供给 ContextListener 调用，用于停机时释放资源
    public static void shutdown() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            System.out.println("WebSocket 进度推送线程池已关闭");
        }
    }

    // 客户端连接成功
    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        System.out.println("WebSocket新连接建立，当前在线数：" + sessions.size());
    }

    // 客户端断开连接
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        System.out.println("WebSocket连接断开，当前在线数：" + sessions.size());
    }

    // 连接异常处理
    @OnError
    public void onError(Session session, Throwable error) {
        error.printStackTrace();
        if (session != null) {
            try {
                session.close();
            } catch (IOException ignored) {}
            sessions.remove(session);
        }
    }
}