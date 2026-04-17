package com.Farstars.org.servlet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@WebServlet("/api/baidu")
public class BaiduAria2Servlet extends HttpServlet {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    // Aria2 RPC 地址
    private static final String ARIA2_RPC_URL = "http://localhost:6800/jsonrpc";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json;charset=UTF-8");

        String action = req.getParameter("action");
        String token = req.getParameter("token");

        if ("list".equals(action)) {
            String dir = req.getParameter("dir");
            // 构造获取目录的百度 API
            String url = String.format("https://pan.baidu.com/rest/2.0/xpan/file?method=list&dir=%s&access_token=%s",
                    java.net.URLEncoder.encode(dir, StandardCharsets.UTF_8), token);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "pan.baidu.com")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                resp.getWriter().write(response.body());
            } catch (InterruptedException e) {
                resp.setStatus(500);
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json;charset=UTF-8");

        // 解析前端发来的 JSON 数据
        JsonObject payload = gson.fromJson(req.getReader(), JsonObject.class);
        String action = payload.get("action").getAsString();

// 在 doPost 方法中，安全提取参数
        if ("download".equals(action)) {
            // 增加空指针防御，确保程序健壮性
            if (!payload.has("filename") || payload.get("filename").isJsonNull()) {
                resp.setStatus(400);
                resp.getWriter().write("{\"success\": false, \"msg\": \"文件名缺失\"}");
                return;
            }

            long fsId = payload.get("fs_id").getAsLong();
            String token = payload.get("token").getAsString();
            String filename = payload.get("filename").getAsString();
            // 👈 接收目录参数
            String dir = payload.has("dir") && !payload.get("dir").isJsonNull()
                    ? payload.get("dir").getAsString() : null;

            try {
                String dlink = getBaiduDlink(fsId, token);
                if (dlink != null) {
                    String finalDownloadUrl = dlink + "&access_token=" + token;
                    // 将 dir 传入推送方法
                    boolean success = pushToAria2(finalDownloadUrl, filename, dir);

                    JsonObject result = new JsonObject();
                    result.addProperty("success", success);
                    resp.getWriter().write(result.toString());
                }
            } catch (Exception e) {
                resp.setStatus(500);
            }
        }
    }

    private String getBaiduDlink(long fsId, String token) throws IOException, InterruptedException {
        String fsidsParam = "[" + fsId + "]";
        String url = String.format("https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&dlink=1&fsids=%s&access_token=%s",
                java.net.URLEncoder.encode(fsidsParam, StandardCharsets.UTF_8), token);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "pan.baidu.com")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

        if (jsonResponse.get("errno").getAsInt() == 0) {
            JsonArray list = jsonResponse.getAsJsonArray("list");
            if (!list.isEmpty()) {
                return list.get(0).getAsJsonObject().get("dlink").getAsString();
            }
        }
        return null;
    }

    private boolean pushToAria2(String url, String filename,String dir) throws IOException, InterruptedException {
        // 构造发送给 Aria2 的 JSON-RPC 请求体
        JsonObject rpcRequest = new JsonObject();
        rpcRequest.addProperty("jsonrpc", "2.0");
        rpcRequest.addProperty("method", "aria2.addUri");
        rpcRequest.addProperty("id", "java-servlet");

        JsonArray params = new JsonArray();

        // 第一个参数：URL 数组
        JsonArray urls = new JsonArray();
        urls.add(url);
        params.add(urls);

        // 第二个参数：Options 对象 (伪装 UA, 设置保存文件名)
        JsonObject options = new JsonObject();
        JsonArray headers = new JsonArray();
        headers.add("User-Agent: pan.baidu.com");
        options.add("header", headers);
        options.addProperty("out", filename); // 指定保存的文件名
        if (dir != null && !dir.trim().isEmpty()) {
            options.addProperty("dir", dir);
        }
        params.add(options);

        rpcRequest.add("params", params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ARIA2_RPC_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rpcRequest.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

        // 如果 Aria2 返回了 result (即任务的 GID)，说明推送成功
        return jsonResponse.has("result");
    }
}