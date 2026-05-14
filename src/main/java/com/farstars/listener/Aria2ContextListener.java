package com.farstars.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class Aria2ContextListener {

    private static final Logger log = LoggerFactory.getLogger(Aria2ContextListener.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(){
        log.info("Aria2 下载器后台应用已启动");
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(){
        log.info("正在关闭 Aria2 下载器后台应用...");
    }
}
