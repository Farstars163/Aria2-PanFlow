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

@WebServlet("/api/quark")
public class QuarkAria2Servlet extends HttpServlet {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    // Aria2 RPC 地址
    private static final String ARIA2_RPC_URL = "http://localhost:6800/jsonrpc";
    // 夸克 PC 客户端专属 UA
    private static final String QUARK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/3.20.0 Chrome/112.0.5615.165 Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json;charset=UTF-8");

        String action = req.getParameter("action");
        String cookie = req.getParameter("cookie");

        if ("list".equals(action)) {
            String pdir = req.getParameter("dir");
            if (pdir == null || pdir.isEmpty() || "/".equals(pdir)) {
                pdir = "0"; // 夸克的根目录 ID 是 "0"
            }


            String webUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            String url = "https://drive.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&_fetch_total=1&_page=1&_size=1000&pdir_fid="
                    + java.net.URLEncoder.encode(pdir, StandardCharsets.UTF_8);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", webUserAgent)
                        .header("Referer", "https://drive.quark.cn/")
                        .header("Cookie", cookie)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                // 拦截 WAF 加密字符串，防止前端解析 JSON 崩溃
                if (body != null && body.startsWith("AATF")) {
                    resp.setStatus(401);
                    resp.getWriter().write("{\"code\": 401, \"msg\": \"被夸克防火墙拦截(WAF)\"}");
                } else {
                    // 正常返回 JSON 目录树
                    resp.getWriter().write(body);
                }
            } catch (InterruptedException e) {
                resp.setStatus(500);
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json;charset=UTF-8");

        JsonObject payload = gson.fromJson(req.getReader(), JsonObject.class);
        String action = payload.get("action").getAsString();

        if ("download".equals(action)) {
            if (!payload.has("filename") || payload.get("filename").isJsonNull()) {
                resp.setStatus(400);
                resp.getWriter().write("{\"success\": false, \"msg\": \"文件名缺失\"}");
                return;
            }

            String fsId = payload.get("fs_id").getAsString();
            String cookie = payload.get("cookie").getAsString();
            String filename = payload.get("filename").getAsString();
            String dir = payload.has("dir") && !payload.get("dir").isJsonNull()
                    ? payload.get("dir").getAsString() : null;

            try {

                String downloadUrl = getQuarkDlink(fsId, cookie);
                if (downloadUrl != null) {
                    boolean success = pushToAria2(downloadUrl, filename, dir, cookie);

                    JsonObject result = new JsonObject();
                    result.addProperty("success", success);
                    resp.getWriter().write(result.toString());
                } else {
                    resp.setStatus(401);
                    resp.getWriter().write("{\"success\": false, \"msg\": \"提取直链失败，请检查 Cookie\"}");
                }
            } catch (Exception e) {
                resp.setStatus(500);
            }
        }
    }

    private String getQuarkDlink(String fsId, String cookie) throws IOException, InterruptedException {
        String url = "https://drive-pc.quark.cn/1/clouddrive/file/download?entry=ft&fr=pc&pr=ucpro";

        JsonObject payload = new JsonObject();
        JsonArray fids = new JsonArray();
        fids.add(fsId);
        payload.add("fids", fids);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", QUARK_UA)
                .header("Referer", "https://drive.quark.cn/")
                .header("Cookie", cookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            return null; // Cookie 失效
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        if (jsonResponse.get("code").getAsInt() == 0) {
            JsonArray dataList = jsonResponse.getAsJsonArray("data");
            if (!dataList.isEmpty()) {
                return dataList.get(0).getAsJsonObject().get("download_url").getAsString();
            }
        }
        return null;
    }

    private boolean pushToAria2(String url, String filename, String dir, String cookie) throws IOException, InterruptedException {
        JsonObject rpcRequest = new JsonObject();
        rpcRequest.addProperty("jsonrpc", "2.0");
        rpcRequest.addProperty("method", "aria2.addUri");
        rpcRequest.addProperty("id", "quark-servlet");

        JsonArray params = new JsonArray();
        JsonArray urls = new JsonArray();
        urls.add(url);
        params.add(urls);

        JsonObject options = new JsonObject();
        JsonArray headers = new JsonArray();
        // 向 Aria2 注入夸克的防盗链信息
        headers.add("User-Agent: " + QUARK_UA);
        headers.add("Referer: https://drive.quark.cn/");
        headers.add("Cookie: " + cookie);

        options.add("header", headers);
        options.addProperty("out", filename);

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

        return jsonResponse.has("result");
    }
}