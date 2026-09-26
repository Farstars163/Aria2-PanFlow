package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.service.Pan123ApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 123 云盘 API (后端直连官方接口, 参考 D:\123pan\pan123_core.py)
 *
 * 不再依赖 pan123_service.exe (其 list 返回空且 GBK 崩溃), 由后端读取 token
 * 直接调用 123 官方 API 实现文件列表与下载推送.
 */
@RestController
@RequestMapping("/api/pan123")
public class Pan123Controller {

    private static final Logger log = LoggerFactory.getLogger(Pan123Controller.class);

    private final Pan123ApiService pan123ApiService;
    private final com.farstars.service.PanServiceManager panServiceManager;

    public Pan123Controller(Pan123ApiService pan123ApiService,
                            com.farstars.service.PanServiceManager panServiceManager) {
        this.pan123ApiService = pan123ApiService;
        this.panServiceManager = panServiceManager;
    }

    /**
     * 文件列表 (parentId 默认 0 根目录)
     */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") String parentId) {
        if (panServiceManager.isPan123Stopped()) {
            return ResponseEntity.ok(Result.error(403, "123云盘服务已停止，请先在页面点击「启动服务」"));
        }
        try {
            return ResponseEntity.ok(Result.ok(pan123ApiService.list(parentId)));
        } catch (Exception e) {
            log.error("123云盘列表失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "123云盘列表失败: " + e.getMessage()));
        }
    }

    /**
     * 推送到 Aria2 (后端解析直链)
     */
    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody Map<String, Object> payload) {
        if (panServiceManager.isPan123Stopped()) {
            return ResponseEntity.ok(Result.error(403, "123云盘服务已停止，请先在页面点击「启动服务」"));
        }
        try {
            String dir = payload.get("dir") == null ? null : payload.get("dir").toString();
            return ResponseEntity.ok(Result.ok(pan123ApiService.download(payload, dir)));
        } catch (Exception e) {
            log.error("123云盘推送失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "123云盘推送失败: " + e.getMessage()));
        }
    }
}
