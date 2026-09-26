package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.domain.DuPan;
import com.farstars.service.Aria2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/baidu")
public class DuPanController {

    private static final Logger log = LoggerFactory.getLogger(DuPanController.class);

    private final RestClient restClient;
    private final Aria2Client aria2Client;
    private final com.farstars.service.CdpClient cdpClient;

    @Value("${baidu.pan.user-agent}")
    private String userAgent;
    @Value("${baidu.pan.list-url}")
    private String listUrl;
    @Value("${baidu.pan.meta-url}")
    private String metaUrl;


    public DuPanController(RestClient.Builder restClientBuilder, Aria2Client aria2Client,
                           com.farstars.service.CdpClient cdpClient) {
        this.restClient = restClientBuilder.build();
        this.aria2Client = aria2Client;
        this.cdpClient = cdpClient;
    }

    /**
     * CDP 自动获取百度 access_token: 扫描调试浏览器页面 URL,
     * 从百度 oob 授权完成页 (login_success#access_token=xxx) 提取并回填, 免手动复制.
     */
    @GetMapping("/cdp-token")
    public ResponseEntity<?> cdpToken() {
        try {
            String token = cdpClient.findAccessTokenInPages();
            if (token == null || token.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "未在调试浏览器中找到 access_token，请先在调试浏览器中完成百度授权（页面地址栏应含 access_token=）"));
            }
            return ResponseEntity.ok(Result.ok(token));
        } catch (Exception e) {
            log.error("CDP 获取百度 token 失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "自动获取失败: " + e.getMessage()));
        }
    }

    /**
     * 百度 OAuth 授权码 → access_token 兑换
     * body: { client_id, client_secret, code }
     */
    @PostMapping("/oauth-token")
    public ResponseEntity<?> exchangeToken(@RequestBody Map<String, String> payload) {
        String clientId = payload.get("client_id");
        String clientSecret = payload.get("client_secret");
        String code = payload.get("code");
        if (clientId == null || clientSecret == null || code == null
                || clientId.isBlank() || clientSecret.isBlank() || code.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失: client_id / client_secret / code"));
        }
        try {
            JsonNode res = restClient.get()
                    .uri("https://openapi.baidu.com/oauth/2.0/token?grant_type=authorization_code&code={code}&client_id={cid}&client_secret={cs}&redirect_uri=oob",
                            code, clientId, clientSecret)
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null || res.has("error")) {
                String msg = res == null ? "空响应" : res.path("error_description").asText(res.path("error").asText());
                return ResponseEntity.ok(Result.error(500, "兑换失败: " + msg));
            }
            String token = res.path("access_token").asText("");
            if (token.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "兑换失败: 响应中无 access_token"));
            }
            return ResponseEntity.ok(Result.ok(Map.of(
                    "access_token", token,
                    "expires_in", res.path("expires_in").asText(""),
                    "refresh_token", res.path("refresh_token").asText("")
            )));
        } catch (Exception e) {
            log.error("百度 token 兑换失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "兑换失败: " + e.getMessage()));
        }
    }

    /**
     * 获取文件列表
     *
     * @param action list
     * @param token  百度网盘 access_token
     * @param dir    目录路径
     */
    @GetMapping
    public ResponseEntity<?> listFiles(
            @RequestParam String action,
            @RequestParam String token,
            @RequestParam(required = false, defaultValue = "/") String dir
    ) {
        if (!"list".equals(action)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        try {
            JsonNode response = restClient.get()
                    .uri(listUrl, dir, token)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(JsonNode.class);
            return ResponseEntity.ok(Result.ok(response));
        } catch (Exception e) {
            log.error("调用百度网盘列表API失败:{}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "调用百度网盘列表API失败"));
        }
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<?> downloadFile(@RequestBody DuPan payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        if (payload.getFilename() == null || payload.getFilename().trim().isEmpty() || payload.getFsId() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失"));
        }

        try {
            String dLink = getBaiduDlink(payload.getFsId(), payload.getToken());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Result.error(400, "解析百度网盘直链失败"));
            }

            // 预解析 dlink 的 302 重定向 → 最终可下载 URL（aria2 直接拉最终地址，绕开其重定向挂起问题）
            String finalDownloadUrl = resolveRedirect(dLink + "&access_token=" + payload.getToken());
            boolean success = pushToAria2(finalDownloadUrl, payload.getFilename(), payload.getDir());
            return ResponseEntity.ok(Result.ok(Map.of("success", success)));
        } catch (Exception e) {
            log.error("Aria2推送失败，请检查Aria2是否启动");
            return ResponseEntity.status(500).body(Result.error(500, "Aria2推送失败，请检查Aria2是否启动"));
        }
    }

    private String getBaiduDlink(Long fsId, String token) throws Exception {
        String fsidsParam = "[" + fsId + "]";
        JsonNode response = restClient.get()
                .uri(metaUrl, fsidsParam, token)
                .header("User-Agent", userAgent)
                .retrieve()
                .body(JsonNode.class);

        if (response != null && response.has("errno") && response.get("errno").asInt() == 0) {
            JsonNode list = response.get("list");
            if (list != null && list.isArray() && !list.isEmpty()) {
                return list.get(0).get("dlink").asText();
            }
        }
        return null;
    }

    /** 预解析 dlink 的 302 重定向，返回最终可下载 URL（aria2 直接拉最终地址，避免其重定向处理挂起） */
    private String resolveRedirect(String url) throws IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "pan.baidu.com");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc != null && !loc.isEmpty()) {
                String next = loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(url), loc).toString();
                return resolveRedirect(next);
            }
        }
        conn.disconnect();
        return url;
    }

    private boolean pushToAria2(String finalDownloadUrl, String filename, String dir) throws Exception {
        log.info("准备推送网盘文件 {} 至下载器, 目标目录: {}", filename, dir);

        try {
            // 百度大文件(>50MB)dlink 拉取必须用纯 "pan.baidu.com" UA，否则 403（netdisk 客户端 UA 会失败）
            String gid = aria2Client.addUri(finalDownloadUrl, dir, filename,
                List.of("User-Agent: pan.baidu.com",
                        "Referer: https://pan.baidu.com/"));
            return gid != null && !gid.isEmpty();
        } catch (IOException e) {
            log.error("推送任务到 Aria2 失败", e);
            return false;
        }
    }
}
