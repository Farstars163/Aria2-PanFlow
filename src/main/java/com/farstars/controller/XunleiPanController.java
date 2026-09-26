package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.domain.Xunlei;
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
 * 迅雷云盘 - 直链解析与 Aria2 推送.
 * 令牌体系参考网盘直链下载助手(v1.1.3.user.js):
 * - 文件列表/直链均走官方 API https://api-pan.xunlei.com/drive/v1
 * - 鉴权头: Authorization: {token_type} {access_token} (来自页面 localStorage credentials_*)
 * - 可选: X-Captcha-Token(验证令牌), X-Device-Id(设备 ID)
 */
@RestController
@RequestMapping("/api/xunlei")
public class XunleiPanController {

    private static final Logger log = LoggerFactory.getLogger(XunleiPanController.class);

    /**
     * 在调试浏览器 pan.xunlei.com 页面执行: 读取 localStorage 中
     * credentials_* / captcha_* / deviceid, 返回 JSON 字符串.
     */
    private static final String TOKEN_JS = """
            (() => {
              const out = {};
              // 与用户脚本 base.getStorage 一致: JSON 解析失败时返回原始字符串
              const read = (key) => { try { return JSON.parse(localStorage.getItem(key)); } catch (e) { return localStorage.getItem(key); } };
              for (let i = 0; i < localStorage.length; i++) {
                const k = localStorage.key(i);
                if (/^credentials_/.test(k)) out.credentials = read(k);
                else if (/^captcha_[\\w]{16}/.test(k)) out.captcha = read(k);
              }
              const did = read('deviceid');
              const m = /(\\w{32})/.exec(typeof did === 'string' ? did.split(',')[0] : '');
              if (m) out.deviceid = m[1];
              return JSON.stringify(out);
            })()
            """;

    /** 迅雷云盘 web 客户端固定 client_id(令牌按其签发, 缺失会被判定为无效令牌) */
    private static final String XUNLEI_CLIENT_ID = "Xqp0kJBXWhwaTpB6";

    private final RestClient restClient;
    private final Aria2Client aria2Client;
    private final ObjectMapper objectMapper;
    private final CdpClient cdpClient;

    @Value("${xunlei.pan.list-url}")
    private String listUrl;
    @Value("${xunlei.pan.file-url}")
    private String fileUrl;
    @Value("${xunlei.pan.referer}")
    private String referer;
    @Value("${xunlei.pan.user-agent}")
    private String userAgent;

    public XunleiPanController(RestClient.Builder restClientBuilder, Aria2Client aria2Client, ObjectMapper objectMapper, CdpClient cdpClient) {
        this.restClient = restClientBuilder.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
        this.cdpClient = cdpClient;
    }

    /**
     * CDP 自动获取迅雷云盘令牌: 连接浏览器调试端口(9222), 在 pan.xunlei.com
     * 页面读取 localStorage 中的 credentials/captcha/deviceid, 免手动复制.
     */
    @GetMapping("/cdp-token")
    public ResponseEntity<?> cdpToken() {
        try {
            Map<String, String> token = fetchTokenFromCdp();
            if (token == null) {
                return ResponseEntity.ok(Result.error(500, "未读取到 access_token，请确认已在调试浏览器中登录迅雷云盘并打开 pan.xunlei.com 页面（必要时刷新页面）"));
            }
            return ResponseEntity.ok(Result.ok(token));
        } catch (Exception e) {
            log.error("CDP 读取迅雷令牌失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "自动获取失败: " + e.getMessage()));
        }
    }

    /**
     * 从调试浏览器 pan.xunlei.com 页面读取 localStorage 中的
     * credentials_* / captcha_* / deviceid 组装令牌(自动获取与下载重试共用).
     */
    private Map<String, String> fetchTokenFromCdp() throws Exception {
        String json = cdpClient.evaluateJs("pan.xunlei.com", TOKEN_JS);
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return null;
        }
        JsonNode node = objectMapper.readTree(json);
        String accessToken = node.path("credentials").path("access_token").asText("");
        if (accessToken.isEmpty()) {
            return null;
        }
        String tokenType = node.path("credentials").path("token_type").asText("Bearer");
        String captchaToken = node.path("captcha").path("token").asText("");
        String deviceId = node.path("deviceid").asText("");
        return Map.of(
                "accessToken", accessToken,
                "tokenType", tokenType,
                "captchaToken", captchaToken,
                "deviceId", deviceId
        );
    }

    /**
     * 获取文件列表
     *
     * @param action       list
     * @param accessToken  迅雷云盘 access_token
     * @param tokenType    令牌类型(默认 Bearer)
     * @param captchaToken 验证令牌(可选)
     * @param deviceId     设备 ID(可选)
     * @param dir          目录 id ("0" 为根目录)
     */
    @GetMapping
    public ResponseEntity<?> listFiles(
            @RequestParam String action,
            @RequestParam String accessToken,
            @RequestParam(required = false, defaultValue = "Bearer") String tokenType,
            @RequestParam(required = false, defaultValue = "") String captchaToken,
            @RequestParam(required = false, defaultValue = "") String deviceId,
            @RequestParam(required = false, defaultValue = "") String dir
    ) {
        if (!"list".equals(action)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }
        // 迅雷根目录的 parent_id 为空字符串(而非 "0"/"/"), 传 "0" 会报"文件(夹)不存在"
        if (dir == null || dir.isEmpty() || "/".equals(dir) || "0".equals(dir)) {
            dir = "";
        }

        try {
            String filters = "{\"phase\":{\"eq\":\"PHASE_TYPE_COMPLETE\"},\"trashed\":{\"eq\":false}}";
            var request = restClient.get()
                    .uri(listUrl + "?parent_id={dir}&with_audit=true&filters={filters}", dir, filters)
                    .header("Authorization", tokenType + " " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("X-Client-Id", XUNLEI_CLIENT_ID)
                    .header("Referer", referer)
                    .header("User-Agent", userAgent);
            if (!deviceId.isEmpty()) request = request.header("x-device-id", deviceId);
            if (!captchaToken.isEmpty()) request = request.header("x-captcha-token", captchaToken);
            // 4xx/5xx 也读取响应体: 迅雷以 HTTP 200 + error_code 或非 200 + 错误体两种方式报错
            String response = request.retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> { })
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            if (node.has("error_code")) {
                int code = node.path("error_code").asInt();
                String desc = node.path("error_description").asText("请刷新页面后重试");
                return ResponseEntity.status(401).body(Result.error(code, "迅雷云盘接口返回: " + desc));
            }
            return ResponseEntity.ok(Result.ok(node));
        } catch (Exception e) {
            log.error("调用迅雷云盘列表API失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "调用迅雷云盘列表API失败"));
        }
    }

    /**
     * 推送迅雷云盘文件到 Aria2
     */
    @PostMapping
    public ResponseEntity<?> downloadFile(@RequestBody Xunlei payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        if (payload.getFileId() == null || payload.getFileId().trim().isEmpty()
                || payload.getAccessToken() == null || payload.getAccessToken().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失"));
        }

        try {
            String dLink = resolveDlinkWithRetry(payload);

            boolean success = pushToAria2(dLink, payload.getFilename(), payload.getDir());
            return ResponseEntity.ok(Result.ok(Map.of("success", success)));
        } catch (Exception e) {
            // getXunleiDlink 抛出的异常带有迅雷真实错误描述, 直接透出便于定位
            log.error("解析迅雷云盘直链失败: {}", e.getMessage());
            String msg = e.getMessage();
            return ResponseEntity.status(500).body(Result.error(500, msg == null || msg.isEmpty() ? "解析迅雷云盘直链失败" : msg));
        }
    }

    /**
     * 解析直链, error_code=9(验证码无效)时自动重试:
     * 1) 重新从调试浏览器抓取最新令牌(用户刷新 pan.xunlei.com 后 localStorage 已更新);
     * 2) 兜底: 去掉验证令牌重试(部分场景下可不带 x-captcha-token).
     */
    private String resolveDlinkWithRetry(Xunlei payload) throws Exception {
        try {
            return getXunleiDlink(payload.getFileId(), payload.getAccessToken(), payload.getTokenType(), payload.getCaptchaToken(), payload.getDeviceId());
        } catch (IOException e) {
            boolean captchaExpired = e.getMessage() != null && e.getMessage().contains("error_code=9");
            if (!captchaExpired) {
                throw e;
            }
            log.info("迅雷验证码无效(error_code=9)，尝试自动刷新令牌重试");
            Exception lastErr = e;
            try {
                Map<String, String> fresh = fetchTokenFromCdp();
                if (fresh != null && !fresh.get("captchaToken").isEmpty()) {
                    return getXunleiDlink(payload.getFileId(), fresh.get("accessToken"), fresh.get("tokenType"), fresh.get("captchaToken"), fresh.get("deviceId"));
                }
            } catch (Exception ex) {
                lastErr = new IOException("自动刷新令牌失败: " + ex.getMessage(), ex);
            }
            try {
                return getXunleiDlink(payload.getFileId(), payload.getAccessToken(), payload.getTokenType(), null, payload.getDeviceId());
            } catch (IOException ignored) {
                // 重试仍失败, 抛出原始 error_code=9 错误(含刷新页面指引)
                throw lastErr;
            }
        }
    }

    /**
     * 解析迅雷云盘文件直链: GET /drive/v1/files/{id} (与用户脚本一致, 不带 query 参数)
     * 返回 web_content_link; error_code==9 表示页面验证过期.
     */
    private String getXunleiDlink(String fileId, String accessToken, String tokenType, String captchaToken, String deviceId) throws Exception {
        String authType = (tokenType == null || tokenType.isEmpty()) ? "Bearer" : tokenType;
        var request = restClient.get()
                .uri(fileUrl, fileId)
                .header("Authorization", authType + " " + accessToken)
                .header("Content-Type", "application/json")
                .header("X-Client-Id", XUNLEI_CLIENT_ID)
                .header("Referer", referer)
                .header("User-Agent", userAgent);
        if (deviceId != null && !deviceId.isEmpty()) request = request.header("x-device-id", deviceId);
        if (captchaToken != null && !captchaToken.isEmpty()) request = request.header("x-captcha-token", captchaToken);
        String response = request.retrieve()
                .onStatus(status -> status.isError(), (req, res) -> { })
                .body(String.class);

        JsonNode node = objectMapper.readTree(response);
        if (node.has("web_content_link")) {
            return node.path("web_content_link").asText();
        }
        if (node.has("error_code")) {
            int code = node.path("error_code").asInt();
            String desc = node.path("error_description").asText("未知错误");
            // error_code==9: 页面验证(captcha)过期, 需刷新 pan.xunlei.com 页面重新获取
            throw new IOException("迅雷云盘接口返回: " + desc + " (error_code=" + code + ")"
                    + (code == 9 ? "，请刷新 pan.xunlei.com 页面后重新自动获取" : ""));
        }
        throw new IOException("迅雷云盘直链解析失败，响应内容异常: " + response);
    }

    private boolean pushToAria2(String dlink, String filename, String dir) {
        log.info("准备推送迅雷云盘文件 {} 至下载器, 目标目录: {}", filename, dir);
        try {
            String gid = aria2Client.addUri(dlink, dir, filename, List.of(
                    "User-Agent: " + userAgent,
                    "Referer: " + referer
            ));
            return gid != null && !gid.isEmpty();
        } catch (IOException e) {
            log.error("推送任务到 Aria2 失败", e);
            return false;
        }
    }
}
