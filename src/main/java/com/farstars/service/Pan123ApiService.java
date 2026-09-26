package com.farstars.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 123 云盘官方 API 直连实现 (参考 D:\123pan\pan123_core.py)
 *
 * 绕开 pan123_service.exe (其 list 返回空且 GBK 崩溃), 由后端直接读取 token 文件
 * 调用 123 官方接口实现文件列表与下载直链推送.
 */
@Service
public class Pan123ApiService {

    private static final Logger log = LoggerFactory.getLogger(Pan123ApiService.class);

    @Value("${pan-service.dir:./python_service}")
    private String serviceDir;

    @Value("${pan-service.pan123-token-file:pan123_token.json}")
    private String tokenFileName;

    /** 123 官方 API 域名 (www.123pan.com 已 404 失效, 需用 www.123pan.cn) */
    private static final String API_BASE = "https://www.123pan.cn";

    /** 固定 LoginUuid (源码在初始化时生成一次) */
    private final String loginUuid = java.util.UUID.randomUUID().toString().replace("-", "");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Aria2Client aria2Client;

    public Pan123ApiService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, Aria2Client aria2Client) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.aria2Client = aria2Client;
    }

    private final java.util.concurrent.atomic.AtomicLong lastTokenCheck = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean lastTokenValid = new java.util.concurrent.atomic.AtomicBoolean(false);
    private long lastTokenFileMtime = -1;

    /**
     * 验证本地 token 是否有效(调用官方根目录列表接口, 带 10 秒缓存避免频繁调用).
     * token 文件被修改(lastModified 变化)时立即重新验证, 消除缓存滞后(改错 token 无需刷新即生效).
     * token 无效/失效(登录失败)返回 false, 触发前端重新扫码.
     */
    public boolean validateToken() {
        long now = System.currentTimeMillis();
        long mtime = tokenFileMtime();
        boolean tokenFileChanged = mtime != lastTokenFileMtime;
        if (!tokenFileChanged && now - lastTokenCheck.get() < 10_000) {
            return lastTokenValid.get();
        }
        lastTokenFileMtime = mtime;
        boolean valid = false;
        try {
            String token = readToken();
            JsonNode res = restClient.get()
                    .uri(API_BASE + "/api/file/list/new?driveId=0&limit=1&next=0&orderBy=file_id&orderDirection=desc&parentFileId=0&trashed=false&SearchData=&Page=1&OnlyLookAbnormalFile=0")
                    .headers(h -> h.putAll(buildHeaders(token)))
                    .retrieve()
                    .body(JsonNode.class);
            valid = res != null && res.path("code").asInt(-1) == 0;
        } catch (Exception e) {
            valid = false;   // token 无效/失效/网络异常, 视为未授权
        }
        lastTokenCheck.set(now);
        lastTokenValid.set(valid);
        return valid;
    }

    /** token 文件修改时间(用于检测 token 被改动时强制重新验证) */
    private long tokenFileMtime() {
        try {
            File f = new File(new File(serviceDir), tokenFileName);
            return f.exists() ? f.lastModified() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读取 token 文件中的 Bearer token */
    private String readToken() throws IOException {
        File f = new File(new File(serviceDir), tokenFileName);
        if (!f.exists() || f.length() == 0) {
            throw new IOException("未找到授权 Token，请先在网页内扫码登录");
        }
        JsonNode node = objectMapper.readTree(Files.readString(f.toPath(), StandardCharsets.UTF_8));
        String token = node.path("token").asText("");
        if (token.isEmpty()) {
            throw new IOException("授权 Token 为空，请重新扫码登录");
        }
        return token;
    }

    private HttpHeaders buildHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", token);
        headers.set("LoginUuid", loginUuid);
        headers.set("content-type", "application/json");
        headers.set("Accept", "*/*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.set("App-Version", "3");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        headers.set("Pragma", "no-cache");
        headers.set("Referer", API_BASE + "/");
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0");
        headers.set("platform", "web");
        headers.set("sec-ch-ua", "\"Microsoft Edge\";v=\"119\", \"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"");
        headers.set("sec-ch-ua-mobile", "?0");
        headers.set("sec-ch-ua-platform", "\"Windows\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 获取文件列表. 返回与旧 pan123_service 兼容的结构 {code, data:[{FileId, FileName, Size, Type, Etag, S3KeyFlag, ...}]}
     */
    public Map<String, Object> list(String parentId) throws IOException {
        String token = readToken();
        String parentFileId = (parentId == null || parentId.isEmpty() || "0".equals(parentId)) ? "0" : parentId;

        JsonNode res = restClient.get()
                .uri(API_BASE + "/api/file/list/new?driveId=0&limit=100&next=0&orderBy=file_id&orderDirection=desc&parentFileId="
                        + parentFileId + "&trashed=false&SearchData=&Page=1&OnlyLookAbnormalFile=0")
                .headers(h -> h.putAll(buildHeaders(token)))
                .retrieve()
                .body(JsonNode.class);

        if (res == null || res.path("code").asInt(-1) != 0) {
            throw new IOException("获取文件列表失败: " + (res == null ? "空响应" : res.path("message").asText("未知错误")));
        }
        // 官方响应结构: {code, message, data: {Next, Len, Total, InfoList:[...]}} — InfoList 在 data 直接下
        JsonNode infoList = res.path("data").path("InfoList");
        List<Map<String, Object>> items = new ArrayList<>();
        if (infoList != null && infoList.isArray()) {
            for (JsonNode n : infoList) {
                Map<String, Object> item = new HashMap<>();
                item.put("FileId", n.path("FileId").asLong());
                item.put("FileName", n.path("FileName").asText());
                item.put("Size", n.path("Size").asLong(0));
                item.put("Type", n.path("Type").asInt(0));
                item.put("Etag", n.path("Etag").asText(""));
                item.put("S3KeyFlag", n.path("S3KeyFlag").asText(""));
                // 兼容前端两种字段命名
                item.put("fileId", n.path("FileId").asLong());
                item.put("fileName", n.path("FileName").asText());
                item.put("size", n.path("Size").asLong(0));
                item.put("type", n.path("Type").asInt(0));
                items.add(item);
            }
        }
        return Map.of("code", 0, "data", items);
    }

    /**
     * 获取下载直链并推送到 Aria2.
     * item 需要 FileId/Etag/S3KeyFlag/Type/FileName/Size.
     */
    public Map<String, Object> download(Map<String, Object> item, String saveDir) throws IOException {
        String token = readToken();
        long fileId = toLong(item.getOrDefault("FileId", item.get("fileId")));
        if (fileId <= 0) {
            throw new IOException("参数缺失: FileId");
        }
        int type = item.get("Type") == null ? (item.get("type") == null ? 0 : ((Number) item.get("type")).intValue()) : ((Number) item.get("Type")).intValue();
        String fileName = (String) (item.get("FileName") != null ? item.get("FileName") : item.get("fileName"));
        String etag = (String) (item.get("Etag") != null ? item.get("Etag") : item.get("etag"));
        String s3keyFlag = (String) (item.get("S3KeyFlag") != null ? item.get("S3KeyFlag") : item.get("s3keyFlag"));
        long size = toLong(item.getOrDefault("Size", item.get("size")));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("driveId", 0);
        payload.put("etag", etag == null ? "" : etag);
        payload.put("fileId", fileId);
        payload.put("s3keyFlag", s3keyFlag == null ? "" : s3keyFlag);
        payload.put("type", type);
        payload.put("fileName", fileName == null ? "" : fileName);
        payload.put("size", size);

        JsonNode res = restClient.post()
                .uri(API_BASE + "/a/api/file/download_info")
                .headers(h -> h.putAll(buildHeaders(token)))
                .body(payload.toString())
                .retrieve()
                .body(JsonNode.class);

        if (res == null || res.path("code").asInt(-1) != 0) {
            throw new IOException("获取下载信息失败: " + (res == null ? "空响应" : res.path("message").asText("未知错误")));
        }
        // 官方响应结构: {code, data: {DownloadUrl, ...}} — DownloadUrl 在 data 直接下
        String downloadUrl = res.path("data").path("DownloadUrl").asText("");
        if (downloadUrl.isEmpty()) {
            throw new IOException("未获取到 DownloadUrl");
        }

        // 跟随 302 重定向获取真实直链, 优先解析 download-v2 的 params(base64 编码的真实直链)
        String realUrl = resolveDownloadUrl(downloadUrl);

        // 推送到 Aria2
        String gid = aria2Client.addUri(realUrl, saveDir, fileName,
                List.of("Referer: https://www.123pan.com/", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));
        return Map.of("success", true, "gid", gid, "url", realUrl);
    }

    /**
     * 解析真实下载直链.
     * 123 的 DownloadUrl 形如 https://web-pro2.123952.com/download-v2/?params=<base64>,
     * params 是 base64(URL编码) 的真实 CDN 直链; 该直链 GET 后可能返回:
     *   - 200: 直接可下载
     *   - 302: 取 Location
     *   - 210: 响应 JSON {code:0, data:{redirect_url:...}}, 取 redirect_url 为最终直链
     */
    private String resolveDownloadUrl(String url) {
        String candidate = url;
        try {
            java.net.URI uri = new java.net.URI(url);
            String query = uri.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && "params".equals(pair.substring(0, eq))) {
                        String b64 = pair.substring(eq + 1);
                        byte[] decoded = java.util.Base64.getDecoder().decode(b64);
                        String realUrl = java.net.URLDecoder.decode(
                                new String(decoded, StandardCharsets.UTF_8), StandardCharsets.UTF_8.name());
                        if (realUrl.startsWith("http")) {
                            candidate = realUrl;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 download-v2 params 失败: {}", e.getMessage());
        }
        return resolveCdnUrl(candidate);
    }

    /** 处理 CDN 直链: 200 直接用, 302 取 Location, 210 取 JSON 的 redirect_url */
    private String resolveCdnUrl(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.123pan.com/");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 302) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null && !location.isEmpty()) return location;
            } else if (code == 210) {
                java.io.InputStream is = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                JsonNode node = objectMapper.readTree(body);
                String redirect = node.path("data").path("redirect_url").asText("");
                if (!redirect.isEmpty()) {
                    log.info("210 响应解析到 redirect_url: {}", redirect.substring(0, Math.min(80, redirect.length())));
                    return redirect;
                }
            }
            conn.disconnect();
            return url;
        } catch (Exception e) {
            log.warn("CDN 直链解析失败, 使用原链接: {}", e.getMessage());
            return url;
        }
    }

    private String followRedirect(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.connect();
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (code == 302 && location != null && !location.isEmpty()) {
                return location;
            }
            return url;
        } catch (Exception e) {
            log.warn("跟随下载重定向失败, 使用原链接: {}", e.getMessage());
            return url;
        }
    }

    private long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
