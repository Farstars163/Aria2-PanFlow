package com.farstars.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * CDP (Chrome DevTools Protocol) 客户端.
 * 连接浏览器调试端口(默认 9222), 通过 Network.getCookies 读取指定站点的
 * 完整 cookie(含 HttpOnly), 用于自动获取夸克网盘 cookie.
 */
@Service
public class CdpClient {

    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取指定 URL 域名的完整 cookie(含 HttpOnly), 返回 "name=value; name=value; ..." 字符串.
     * 浏览器需以 --remote-debugging-port=9222 启动.
     */
    public String getCookies(String url) throws Exception {
        // 强制直连(不走系统代理): 系统代理会拦截 127.0.0.1 导致 ConnectException
        HttpClient http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();

        // 1. 获取页面 target 的 webSocketDebuggerUrl
        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:9222/json/list"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET().build();
        String listJson;
        try {
            listJson = http.send(listReq, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            // 9222 未监听(调试浏览器未运行)等连接类错误
            String detail = describe(e);
            throw new java.io.IOException("无法连接浏览器调试端口(9222)，请确认通过 Aria2PanFlow.exe 启动的浏览器仍在运行。详情: " + detail, e);
        }
        JsonNode arr = objectMapper.readTree(listJson);
        String wsUrl = null;
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if ("page".equals(t.path("type").asText())) {
                    wsUrl = t.path("webSocketDebuggerUrl").asText("");
                    if (!wsUrl.isEmpty()) break;
                }
            }
        }
        if (wsUrl == null || wsUrl.isEmpty()) {
            throw new java.io.IOException("未找到浏览器调试页面，请确认浏览器已以调试模式(--remote-debugging-port=9222)启动");
        }

        // 2. WebSocket 发送 Network.getCookies 命令
        CompletableFuture<String> future = new CompletableFuture<>();
        WebSocket ws;
        try {
            ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        final StringBuilder sb = new StringBuilder();
                        @Override
                        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            sb.append(data);
                            if (last) {
                                try {
                                    JsonNode n = objectMapper.readTree(sb.toString());
                                    if (n.path("id").asInt(-1) == 1 && n.has("result")) {
                                        StringBuilder cs = new StringBuilder();
                                        for (JsonNode c : n.path("result").path("cookies")) {
                                            if (cs.length() > 0) cs.append("; ");
                                            cs.append(c.path("name").asText()).append("=").append(c.path("value").asText());
                                        }
                                        future.complete(cs.toString());
                                    }
                                } catch (Exception e) {
                                    future.completeExceptionally(e);
                                }
                            }
                            return WebSocket.Listener.super.onText(w, data, last);
                        }
                        @Override
                        public void onError(WebSocket w, Throwable error) {
                            future.completeExceptionally(error);
                        }
                    }).join();
        } catch (Exception e) {
            String detail = describe(e);
            throw new java.io.IOException("CDP WebSocket 连接失败。详情: " + detail, e);
        }

        String cmd = "{\"id\":1,\"method\":\"Network.getCookies\",\"params\":{\"urls\":[\"" + url + "\"]}}";
        ws.sendText(cmd, true);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            String detail = describe(e);
            throw new java.io.IOException("CDP 读取 cookie 超时/失败。详情: " + detail, e);
        } finally {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) { }
        }
    }

    /**
     * 扫描调试浏览器所有页面 URL, 提取 access_token (百度 oob 授权完成后
     * 页面 URL 形如 https://openapi.baidu.com/oauth/2.0/login_success#access_token=xxx).
     */
    public String findAccessTokenInPages() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();
        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:9222/json/list"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET().build();
        String listJson;
        try {
            listJson = http.send(listReq, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new java.io.IOException("无法连接浏览器调试端口(9222)，请确认通过 Aria2PanFlow.exe 启动的浏览器仍在运行。详情: " + describe(e), e);
        }
        JsonNode arr = objectMapper.readTree(listJson);
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if (!"page".equals(t.path("type").asText())) continue;
                String url = t.path("url").asText("");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("[?&#]access_token=([^&#]+)")
                        .matcher(url);
                if (m.find()) {
                    return java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /**
     * 通过 CDP Runtime.evaluate 在调试浏览器中执行 JS 并返回结果字符串.
     * urlPattern 用于匹配页面 URL(如 "pan.xunlei.com"), 保证在正确的站点上下文执行
     * (读取该站 localStorage 等). 浏览器需以 --remote-debugging-port=9222 启动.
     */
    public String evaluateJs(String urlPattern, String expression) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();

        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:9222/json/list"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET().build();
        String listJson;
        try {
            listJson = http.send(listReq, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new java.io.IOException("无法连接浏览器调试端口(9222)，请确认通过 Aria2PanFlow.exe 启动的浏览器仍在运行。详情: " + describe(e), e);
        }
        JsonNode arr = objectMapper.readTree(listJson);
        String wsUrl = null;
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if ("page".equals(t.path("type").asText())
                        && t.path("url").asText("").contains(urlPattern)) {
                    wsUrl = t.path("webSocketDebuggerUrl").asText("");
                    if (!wsUrl.isEmpty()) break;
                }
            }
        }
        if (wsUrl == null || wsUrl.isEmpty()) {
            throw new java.io.IOException("未找到匹配 " + urlPattern + " 的调试页面，请确认浏览器已打开并登录 " + urlPattern);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        WebSocket ws;
        try {
            ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        final StringBuilder sb = new StringBuilder();
                        @Override
                        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            sb.append(data);
                            if (last) {
                                try {
                                    JsonNode n = objectMapper.readTree(sb.toString());
                                    if (n.path("id").asInt(-1) == 1 && n.has("result")) {
                                        JsonNode exc = n.path("result").path("exceptionDetails");
                                        if (!exc.isMissingNode() && !exc.isNull()) {
                                            future.completeExceptionally(new java.io.IOException("CDP 执行 JS 异常: " + exc.path("text").asText()));
                                            return WebSocket.Listener.super.onText(w, data, last);
                                        }
                                        JsonNode v = n.path("result").path("result").path("value");
                                        future.complete(v.isNull() ? "" : v.asText());
                                    }
                                } catch (Exception e) {
                                    future.completeExceptionally(e);
                                }
                            }
                            return WebSocket.Listener.super.onText(w, data, last);
                        }
                        @Override
                        public void onError(WebSocket w, Throwable error) {
                            future.completeExceptionally(error);
                        }
                    }).join();
        } catch (Exception e) {
            throw new java.io.IOException("CDP WebSocket 连接失败。详情: " + describe(e), e);
        }

        String cmd = "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":" + toJson(expression) + ",\"returnByValue\":true}}";
        ws.sendText(cmd, true);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new java.io.IOException("CDP 执行 JS 超时/失败。详情: " + describe(e), e);
        } finally {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) { }
        }
    }

    /** 将字符串编码为 JSON 字符串字面量(用于拼接 CDP 命令) */
    private static String toJson(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** 提取异常可读信息(兼容 CompletionException getMessage() 为 null 的情况) */
    private static String describe(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                return cur.getMessage();
            }
            cur = cur.getCause();
            depth++;
        }
        return t == null ? "未知错误" : t.toString();
    }
}
