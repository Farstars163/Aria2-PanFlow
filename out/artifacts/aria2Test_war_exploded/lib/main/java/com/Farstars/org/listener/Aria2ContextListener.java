package com.Farstars.org.listener;

import com.Farstars.org.servlet.ProgressWebSocket;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class Aria2ContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("Aria2 下载器后台应用已启动");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("正在关闭 Aria2 下载器后台应用...");
        // 关闭 WebSocket 线程池
        ProgressWebSocket.shutdown();
    }
}
