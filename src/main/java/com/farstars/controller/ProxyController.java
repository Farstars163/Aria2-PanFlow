package com.farstars.controller;

import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 代理设置: 下载走代理地址(如 127.0.0.1:30808), 加速国外链接
 */
@RestController
@RequestMapping("/api/settings")
public class ProxyController {

    private static final Path PROXY_FILE = Path.of("data", "proxy.txt");

    @GetMapping("/proxy")
    public Map<String, Object> getProxy() {
        Map<String, Object> r = new HashMap<>();
        r.put("proxy", readProxy());
        return r;
    }

    @PostMapping("/proxy")
    public Map<String, Object> setProxy(@RequestBody Map<String, Object> body) {
        Map<String, Object> r = new HashMap<>();
        try {
            Object v = body.get("proxy");
            String proxy = v == null ? "" : String.valueOf(v).trim();
            if (proxy.isEmpty()) {
                Files.deleteIfExists(PROXY_FILE);
            } else {
                Files.createDirectories(PROXY_FILE.getParent());
                Files.writeString(PROXY_FILE, proxy, StandardCharsets.UTF_8);
            }
            r.put("ok", true);
            r.put("proxy", proxy);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", String.valueOf(e.getMessage()));
        }
        return r;
    }

    /// 代理地址格式检测 + 连通性测试(经该代理访问公网 204 端点, 5 秒超时)
    @PostMapping("/proxy/test")
    public Map<String, Object> testProxy(@RequestBody Map<String, Object> body) {
        Map<String, Object> r = new HashMap<>();
        String proxy = body.get("proxy") == null ? "" : String.valueOf(body.get("proxy")).trim();
        String host; int port;
        try {
            String[] parts = proxy.split(":");
            if (parts.length != 2 || parts[0].trim().isEmpty()) throw new IllegalArgumentException();
            host = parts[0].trim();
            port = Integer.parseInt(parts[1].trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
        } catch (Exception e) {
            r.put("ok", false);
            r.put("formatError", "代理地址格式不正确，应为 主机:端口，如 127.0.0.1:30808");
            return r;
        }
        try {
            // 超时给足(VPN 代理建连较慢): 连接 15s + 请求 15s
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port)))
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://www.gstatic.com/generate_204"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            r.put("ok", true);
            r.put("reachable", resp.statusCode() == 204 || resp.statusCode() == 200);
        } catch (Exception ex) {
            r.put("ok", false);
            r.put("reachable", false);
            r.put("msg", "代理不可达: " + ex.getMessage());
        }
        return r;
    }

    public static String readProxy() {
        try {
            if (Files.exists(PROXY_FILE)) {
                return Files.readString(PROXY_FILE, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) { }
        return "";
    }
}
