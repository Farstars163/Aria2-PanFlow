package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.domain.Uc;
import com.farstars.service.Aria2Client;
import com.farstars.service.CdpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * UC网盘 - 直链解析与 Aria2 推送 (与夸克同构, 阿里系接口).
 * 参考网盘直链下载助手(v1.1.3.user.js)与 AList quark_uc 驱动:
 * - 列表: GET pc-api.uc.cn/1/clouddrive/file/sort (Cookie 鉴权, 根目录 pdir_fid=0)
 * - 直链: POST pc-api.uc.cn/1/clouddrive/file/download {fids:[...]} -> data[].download_url
 */
@RestController
@RequestMapping("/api/uc")
public class UcPanController {

    private static final Logger log = LoggerFactory.getLogger(UcPanController.class);

    private final RestClient restClient;
    private final Aria2Client aria2Client;
    private final ObjectMapper objectMapper;
    private final CdpClient cdpClient;

    @Value("${uc.pan.UC_UA}")
    private String userAgent;
    @Value("${uc.pan.WEB_UA}")
    private String WEB_UA;
    @Value("${uc.pan.list-url}")
    private String listUrl;
    @Value("${uc.pan.Referer}")
    private String referer;
    @Value("${uc.pan.meta-url}")
    private String metaUrl;

    public UcPanController(RestClient.Builder restClientBuilder, Aria2Client aria2Client, ObjectMapper objectMapper, CdpClient cdpClient) {
        this.restClient = restClientBuilder.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
        this.cdpClient = cdpClient;
    }

    /**
     * CDP 自动获取 UC网盘 cookie(含 HttpOnly): 连接浏览器调试端口(9222)读取, 免手动复制.
     */
    @GetMapping("/cdp-cookie")
    public ResponseEntity<?> cdpCookie() {
        try {
            String cookie = cdpClient.getCookies("https://drive.uc.cn");
            if (cookie.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "未读取到 cookie，请确认已在调试浏览器中登录 UC网盘"));
            }
            return ResponseEntity.ok(Result.ok(cookie));
        } catch (Exception e) {
            log.error("CDP 读取 UC cookie 失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "自动获取失败: " + e.getMessage()));
        }
    }

    /**
     * 获取文件列表
     *
     * @param action list
     * @param cookie UC网盘 Cookie
     * @param dir    目录 id (0 为根目录)
     */
    @GetMapping
    public ResponseEntity<?> listFiles(
            @RequestParam String action,
            @RequestParam String cookie,
            @RequestParam(required = false, defaultValue = "0") String dir
    ) {
        if ("/".equals(dir))
            dir = "0";
        if (!"list".equals(action)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        try {
            // 4xx/5xx 也读取响应体: UC 以 HTTP 401 + 错误体表示 Cookie 失效, 需解析真实 message
            String response = restClient.get()
                    .uri(listUrl, dir)
                    .header("User-Agent", WEB_UA)
                    .header("Cookie", cookie)
                    .header("Referer", referer)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> { })
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return ResponseEntity.status(401).body(Result.error(401, "Cookie已失效或请求被拒绝，请重新抓取"));
            }
            JsonNode node = objectMapper.readTree(response);
            // UC 列表成功: status==200 且 data.list 存在; 失败透出 message
            if (node.path("status").asInt(-1) == 401 || node.path("code").asInt(-1) == 401) {
                return ResponseEntity.status(401).body(Result.error(401, "Cookie已失效，请重新抓取"));
            }
            if (node.path("status").asInt(-1) != 200 && !node.path("data").has("list")) {
                String msg = node.path("message").asText("调用UC网盘列表接口失败");
                return ResponseEntity.status(500).body(Result.error(500, msg));
            }
            return ResponseEntity.ok(Result.ok(node));
        } catch (Exception e) {
            log.error("调用UC网盘列表API失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "调用UC网盘列表API失败"));
        }
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<?> downloadFile(@RequestBody Uc payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        if (payload.getFilename() == null || payload.getFilename().trim().isEmpty() || payload.getFsId() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失"));
        }

        try {
            String dLink = getUcDlink(payload.getFsId(), payload.getCookie());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Result.error(400, "解析UC网盘直链失败"));
            }

            boolean success = pushToAria2(dLink, payload.getFilename(), payload.getDir(), payload.getCookie());
            return ResponseEntity.ok(Result.ok(Map.of("success", success)));
        } catch (Exception e) {
            log.error("Aria2推送失败，请检查Aria2是否启动: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "Aria2推送失败，请检查Aria2是否启动"));
        }
    }

    /**
     * 解析 UC 文件直链: POST /1/clouddrive/file/download {fids:[fsId]} -> data[0].download_url
     * 错误码: 31001 未登录 / 23018 超出游客大小限制
     */
    private String getUcDlink(String fsId, String cookie) throws Exception {
        Map<String, Object> payload = Map.of("fids", List.of(fsId));

        String response = restClient.post()
                .uri(metaUrl)
                .header("Cookie", cookie)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .body(payload)
                .retrieve()
                .body(String.class);

        JsonNode node = objectMapper.readTree(response);
        if (node.has("data") && node.path("data").isArray() && node.path("data").size() > 0) {
            return node.path("data").get(0).path("download_url").asText(null);
        }
        int code = node.path("code").asInt(-1);
        if (code == 31001) {
            log.error("UC网盘未登录(code=31001)，请重新抓取 Cookie");
        } else if (code == 23018) {
            log.error("UC网盘超出游客可获取大小限制(code=23018)，请登录后获取");
        } else {
            log.error("UC网盘获取直链失败: code={} message={}", code, node.path("message").asText(""));
        }
        return null;
    }

    private boolean pushToAria2(String dlink, String filename, String dir, String cookie) {
        log.info("准备推送UC网盘文件 {} 至下载器, 目标目录: {}", filename, dir);
        try {
            String gid = aria2Client.addUri(dlink, dir, filename, List.of(
                    "User-Agent: " + userAgent,
                    "Referer:" + referer,
                    "Cookie:" + cookie
            ));
            return gid != null && !gid.isEmpty();
        } catch (IOException e) {
            log.error("推送任务到 Aria2 失败", e);
            return false;
        }
    }
}
