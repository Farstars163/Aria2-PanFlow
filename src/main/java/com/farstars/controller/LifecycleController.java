package com.farstars.controller;

import com.farstars.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生命周期接口: 供启动器(exe/start.command)感知浏览器页面状态,
 * 实现"关闭浏览器 → 自动停止服务".
 */
@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleController {

    private final AtomicBoolean pageClosed = new AtomicBoolean(false);
    private final AtomicLong closedAt = new AtomicLong(0);

    /** 页面打开(重置关闭标记) */
    @PostMapping("/page-open")
    public ResponseEntity<Result> pageOpen() {
        pageClosed.set(false);
        return ResponseEntity.ok(Result.ok(Map.of("closed", false)));
    }

    /** 页面关闭(浏览器 beforeunload 时由前端上报) */
    @PostMapping("/page-closed")
    public ResponseEntity<Result> pageClosed() {
        pageClosed.set(true);
        closedAt.set(System.currentTimeMillis());
        return ResponseEntity.ok(Result.ok(Map.of("closed", true)));
    }

    /** 查询页面状态 */
    @GetMapping("/status")
    public ResponseEntity<Result> status() {
        return ResponseEntity.ok(Result.ok(Map.of(
                "closed", pageClosed.get(),
                "closedAt", closedAt.get()
        )));
    }
}
