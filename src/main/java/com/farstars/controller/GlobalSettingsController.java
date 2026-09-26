package com.farstars.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局设置: 下载参数(同时下载最大任务数/每服务器最大连接数/断点续传/下载 UA)
 * + RPC/服务端口(存储) + 前端行为开关; 保存时通过 aria2 RPC 应用下载参数
 */
@RestController
@RequestMapping("/api/settings")
public class GlobalSettingsController {

    private static final Path FILE = Path.of("data", "settings.json");
    private static final ObjectMapper OM = new ObjectMapper();
    private static final int ARIA2_RPC_PORT = 16800;

    /// 默认设置
    private static ObjectNode defaults() {
        ObjectNode n = OM.createObjectNode();
        n.put("maxConcurrent", 5);          // 同时下载最大任务数
        n.put("maxConnections", 16);        // 每服务器最大连接数
        n.put("continue", true);            // 断点续传
        n.put("ua", "");                    // 下载 UA(非网盘文件; 空=默认)
        n.put("rpcPort", 16800);            // aria2 RPC 监听端口
        n.put("rpcSecret", "");             // aria2 RPC 授权密钥
        n.put("servicePort", 18080);        // 程序/网盘服务监听端口
        n.put("autoJump", true);            // 新建任务后自动跳转下载页面
        n.put("notifyDone", true);          // 下载完成后通知
        n.put("confirmDelete", true);       // 删除任务前确认
        return n;
    }

    private static JsonNode readSettings() {
        try {
            if (Files.exists(FILE)) {
                JsonNode n = OM.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
                if (n != null && n.isObject()) return n;
            }
        } catch (Exception ignored) { }
        return defaults();
    }

    @GetMapping("/global")
    public Map<String, Object> getGlobal() {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("settings", readSettings());
        return r;
    }

    @PostMapping("/global")
    public Map<String, Object> setGlobal(@RequestBody Map<String, Object> body) {
        Map<String, Object> r = new HashMap<>();
        try {
            ObjectNode s = defaults();
            JsonNode cur = readSettings();
            if (cur != null && cur.isObject()) s.setAll((ObjectNode) cur);
            for (Map.Entry<String, Object> e : body.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) s.put(e.getKey(), (Boolean) v);
                else if (v instanceof Number) s.put(e.getKey(), ((Number) v).intValue());
                else s.put(e.getKey(), String.valueOf(v));
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, OM.writerWithDefaultPrettyPrinter().writeValueAsString(s), StandardCharsets.UTF_8);
            r.put("ok", true);
            r.put("settings", s);
            // 应用下载参数到 aria2(非网盘 UA 仅当设置时)
            applyAria2Options(s);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", String.valueOf(e.getMessage()));
        }
        return r;
    }

    /// 通过 aria2 RPC changeGlobalOption 应用下载参数(读取 aria2.conf 的 rpc-secret)
    private void applyAria2Options(ObjectNode s) {
        try {
            String secret = readAria2Secret();
            ObjectNode opts = OM.createObjectNode();
            opts.put("max-concurrent-downloads", s.path("maxConcurrent").asInt(5));
            opts.put("max-connection-per-server", s.path("maxConnections").asInt(16));
            opts.put("continue", s.path("continue").asBoolean(true) ? "true" : "false");
            String ua = s.path("ua").asText("");
            // UA 设置只用于非网盘文件; 为空则保持 aria2 默认
            if (!ua.isEmpty()) opts.put("user-agent", ua);
            ObjectNode body = OM.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", "settings");
            body.put("method", "aria2.changeGlobalOption");
            var params = OM.createArrayNode();
            params.add("token:" + secret);
            params.add(opts);
            body.set("params", params);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + ARIA2_RPC_PORT + "/jsonrpc"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)))
                    .build();
            client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) { }
    }

    private String readAria2Secret() {
        try {
            Path conf = Path.of("aria2", "windows-x64", "aria2.conf");
            if (!Files.exists(conf)) conf = Path.of("aria2", "aria2.conf");
            for (String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.toLowerCase().startsWith("rpc-secret")) {
                    int eq = t.indexOf('=');
                    if (eq > 0) return t.substring(eq + 1).trim();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /// 启动时自动拉取最新 tracker 列表并应用到 aria2(失败静默, 不影响启动)
    @jakarta.annotation.PostConstruct
    public void init() {
        new Thread(() -> {
            try { updateTrackers(); } catch (Exception ignored) { }
        }).start();
    }

    /// 拉取 tracker 列表: 优先 gitcode 仓库; gitcode raw 返回页面无内容时回退原始 GitHub raw(同一项目上游, 内容一致)
    private void updateTrackers() throws Exception {
        String list = extractTrackers(fetchText("https://gitcode.com/GitHub_Trending/tr/trackerslist/raw/master/trackers_all_ip.txt"));
        if (list.isEmpty()) {
            list = extractTrackers(fetchText("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_all_ip.txt"));
        }
        if (list.isEmpty()) return;
        String secret = readAria2Secret();
        ObjectNode opts = OM.createObjectNode();
        opts.put("bt-tracker", list);
        ObjectNode body = OM.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", "trackers");
        body.put("method", "aria2.changeGlobalOption");
        var params = OM.createArrayNode();
        params.add("token:" + secret);
        params.add(opts);
        body.set("params", params);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + ARIA2_RPC_PORT + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)))
                .build();
        client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private String fetchText(String url) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .GET().build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }

    private String extractTrackers(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:udp|http|wss|ws)://[^\\s<>\\\"]+");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuilder list = new StringBuilder();
        while (m.find()) {
            if (list.length() > 0) list.append(',');
            list.append(m.group());
        }
        return list.toString();
    }
}
