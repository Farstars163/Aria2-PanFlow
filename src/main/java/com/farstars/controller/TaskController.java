package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.service.Aria2Client;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aria2 任务管理 REST API
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final Aria2Client aria2Client;

    public TaskController(Aria2Client aria2Client) {
        this.aria2Client = aria2Client;
    }

    /**
     * 添加下载任务
     * body: { url, dir?, out?, headers?[] }
     */
    @PostMapping("/add")
    public ResponseEntity<Result> add(@RequestBody Map<String, Object> body) {
        Object urlObj = body.get("url");
        String url = urlObj == null ? null : urlObj.toString();
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "下载链接不能为空"));
        }
        String dir = body.get("dir") == null ? null : body.get("dir").toString();
        String filename = body.get("out") == null ? null : body.get("out").toString();
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) body.get("headers");

        try {
            String gid = aria2Client.addUri(url, dir, filename, headers);
            Map<String, Object> data = new HashMap<>();
            data.put("gid", gid);
            data.put("url", url);
            if (dir != null && !dir.isEmpty()) {
                data.put("dir", dir);
            }
            return ResponseEntity.ok(Result.ok(data));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "任务提交失败: " + e.getMessage()));
        }
    }

    /**
     * 添加种子任务(磁力链可直接用 /add 传 magnet: 链接; 此处接收 .torrent 文件内容 base64)
     * body: { torrent: base64, dir?, out? }
     */
    @PostMapping("/torrent")
    public ResponseEntity<Result> addTorrent(@RequestBody Map<String, Object> body) {
        Object torrentObj = body.get("torrent");
        String torrent = torrentObj == null ? null : torrentObj.toString().trim();
        if (torrent == null || torrent.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "种子文件内容不能为空"));
        }
        String dir = body.get("dir") == null ? null : body.get("dir").toString();
        String filename = body.get("out") == null ? null : body.get("out").toString();
        try {
            String gid = aria2Client.addTorrent(torrent, dir, filename);
            return ResponseEntity.ok(Result.ok(Map.of("gid", gid)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "种子任务提交失败: " + e.getMessage()));
        }
    }

    /**
     * 任务总览(启动时 REST 拉取一次全量数据)
     */
    @GetMapping("/overview")
    public ResponseEntity<Result> overview() {
        try {
            ArrayNode active = aria2Client.tellActive();
            ArrayNode waiting = aria2Client.tellWaiting();
            ArrayNode stopped = aria2Client.tellStopped();
            ObjectNode globalStat = aria2Client.getGlobalStat();

            String totalSpeed = "0";
            if (globalStat != null && globalStat.has("result")) {
                totalSpeed = globalStat.get("result").get("downloadSpeed").asText();
            }

            Map<String, Object> data = new HashMap<>();
            data.put("active", active);
            data.put("waiting", waiting);
            data.put("complete", stopped);
            data.put("totalSpeed", totalSpeed);
            return ResponseEntity.ok(Result.ok(data));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Result.error(502, "Aria2 服务不可用: " + e.getMessage()));
        }
    }

    /**
     * 查询单个任务详情
     */
    @GetMapping("/{gid}")
    public ResponseEntity<Result> status(@PathVariable String gid) {
        try {
            return ResponseEntity.ok(Result.ok(aria2Client.tellStatus(gid)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }

    /**
     * 暂停任务
     */
    @PostMapping("/{gid}/pause")
    public ResponseEntity<Result> pause(@PathVariable String gid) {
        try {
            return ResponseEntity.ok(Result.ok(aria2Client.pause(gid)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }

    /**
     * 继续任务
     */
    @PostMapping("/{gid}/unpause")
    public ResponseEntity<Result> unpause(@PathVariable String gid) {
        try {
            return ResponseEntity.ok(Result.ok(aria2Client.unpause(gid)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }

    /**
     * 移除任务: 删除本地文件痕迹(已下载部分文件 + aria2 控制文件), 并清除下载记录
     */
    @PostMapping("/{gid}/remove")
    public ResponseEntity<Result> remove(@PathVariable String gid) {
        String removed = "";
        try {
            // 1. 先获取任务文件路径(必须在 remove 前, 移除后任务状态即不存在)
            java.util.List<String> paths = new java.util.ArrayList<>();
            try {
                ObjectNode status = aria2Client.tellStatus(gid);
                var files = status.path("files");
                if (files != null && files.isArray()) {
                    for (var f : files) {
                        String path = f.path("path").asText("");
                        if (!path.isEmpty()) paths.add(path);
                    }
                }
                // 清理上传的种子元数据文件(<下载目录>/<infoHash>.torrent, 按本任务 hash 精确定位, 不误删其他种子)
                String torDir = status.path("dir").asText("");
                String infoHash = status.path("bittorrent").path("infoHash").asText("");
                if (!torDir.isEmpty() && !infoHash.isEmpty()) {
                    java.io.File tor = new java.io.File(torDir, infoHash + ".torrent");
                    if (tor.exists()) tor.delete();
                }
            } catch (Exception ignored) {
                // 任务可能已停止, 文件信息不可得时跳过
            }
            // 2. 先从 aria2 移除任务(停止写入并释放 .aria2 文件句柄), 再删除本地痕迹
            try {
                removed = aria2Client.remove(gid);
            } catch (Exception e) {
                try { removed = aria2Client.removeDownloadResult(gid); } catch (Exception ignored) { }
            }
            try {
                aria2Client.removeDownloadResult(gid);
            } catch (Exception ignored) { }
            // 3. 句柄释放后删除本地文件与 .aria2(短延时 + 重试, 防下载中句柄占用删不掉)
            for (String path : paths) {
                try {
                    java.io.File file = new java.io.File(path);
                    java.io.File ctl = new java.io.File(path + ".aria2");
                    for (int i = 0; i < 3; i++) {
                        if (file.exists()) file.delete();
                        if (ctl.exists()) ctl.delete();
                        if (!file.exists() && !ctl.exists()) break;
                        Thread.sleep(300);   // 等 aria2 释放句柄后重试
                    }
                } catch (Exception ignored) { }
            }
            return ResponseEntity.ok(Result.ok(removed));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }

    /**
     * 清除指定任务下载记录(仅移除记录, 不影响已下载文件)
     */
    /// 结束做种/已完成种子任务(停止做种并从列表移除, 保留已下载文件; 已停止任务直接清记录)
    @PostMapping("/{gid}/finish")
    public ResponseEntity<Result> finish(@PathVariable String gid) {
        try {
            String r = aria2Client.remove(gid);
            try { aria2Client.removeDownloadResult(gid); } catch (Exception ignored) { }
            return ResponseEntity.ok(Result.ok(r));
        } catch (Exception e) {
            // 已停止/完成的任务无法 remove, 直接清除记录使其从列表消失
            try {
                aria2Client.removeDownloadResult(gid);
                return ResponseEntity.ok(Result.ok("done"));
            } catch (Exception e2) {
                return ResponseEntity.internalServerError().body(Result.error(500, e2.getMessage()));
            }
        }
    }

    /// 获取 BT 任务的 peer 列表(种子 IP/做种状态), 供前端侧边栏展示
    @GetMapping("/{gid}/peers")
    public ResponseEntity<Result> peers(@PathVariable String gid) {
        try {
            tools.jackson.databind.node.ArrayNode peers = aria2Client.getPeers(gid);
            return ResponseEntity.ok(Result.ok(peers));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }

    @PostMapping("/{gid}/clear")
    public ResponseEntity<Result> clear(@PathVariable String gid) {
        try {
            return ResponseEntity.ok(Result.ok(aria2Client.removeDownloadResult(gid)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, e.getMessage()));
        }
    }
}
