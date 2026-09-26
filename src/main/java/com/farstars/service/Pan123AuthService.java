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
import java.util.List;
import java.util.Map;

/**
 * 123 云盘扫码登录 (直接对接 123 官方登录 API, 参考 D:\123pan\pan123_QR_login.py)
 *
 * 流程: qr-code/generate 取二维码文本 → 前端生成二维码图片 → 轮询 qr-code/result
 *      → 扫码确认后 qr-code/wx_code 换 wechat_code → sign_in 换 token → 写入 token 文件并启动服务
 */
@Service
public class Pan123AuthService {

    private static final Logger log = LoggerFactory.getLogger(Pan123AuthService.class);

    @Value("${pan123.auth.base-url:https://login.123pan.com/api/user}")
    private String authBaseUrl;

    @Value("${pan-service.dir:./python_service}")
    private String serviceDir;

    @Value("${pan-service.pan123-token-file:pan123_token.json}")
    private String tokenFileName;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final PanServiceManager panServiceManager;

    public Pan123AuthService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                             PanServiceManager panServiceManager) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.panServiceManager = panServiceManager;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set("Referer", "https://www.123pan.com/");
        headers.set("platform", "web");
        headers.set("origin", "https://www.123pan.com");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 第一步: 生成二维码数据. 返回 { uniID, qrText }.
     * qrText 即为二维码图片内容, 前端用二维码库渲染展示.
     */
    public Map<String, Object> generateQr() throws IOException {
        JsonNode res = restClient.get()
                .uri(authBaseUrl + "/qr-code/generate")
                .headers(h -> h.putAll(buildHeaders()))
                .retrieve()
                .body(JsonNode.class);

        if (res == null || res.path("code").asInt(999) != 0) {
            throw new IOException("获取二维码失败: " + (res == null ? "空响应" : res.toString()));
        }
        String authUrl = res.path("data").path("url").asText();
        String uniId = res.path("data").path("uniID").asText();
        String qrText = authUrl + "?env=production&uniID=" + uniId + "&source=123pan&type=login";
        return Map.of("uniID", uniId, "qrText", qrText);
    }

    /**
     * 第二步: 轮询扫码状态.
     * loginStatus: 0=等待扫码, 1=已扫码待确认, 4=二维码过期, 其它=确认成功
     */
    public Map<String, Object> pollStatus(String uniId) throws IOException {
        JsonNode res = restClient.get()
                .uri(authBaseUrl + "/qr-code/result?uniID=" + uniId)
                .headers(h -> h.putAll(buildHeaders()))
                .retrieve()
                .body(JsonNode.class);

        if (res == null) {
            return Map.of("success", false, "msg", "空响应");
        }
        int code = res.path("code").asInt(999);
        if (code != 0) {
            return Map.of("success", false, "code", code,
                    "msg", res.path("message").asText("查询失败"));
        }
        JsonNode data = res.path("data");
        return Map.of(
                "success", true,
                "loginStatus", data.path("loginStatus").asInt(),
                "scanPlatform", data.path("scanPlatform").asInt()
        );
    }

    /**
     * 第三步: 扫码确认后换取 token 并写入文件, 自动启动 123 云盘服务.
     */
    public Map<String, Object> confirmLogin(String uniId) throws IOException {
        // 1. 换 wechat_code
        ObjectNode wxPayload = objectMapper.createObjectNode();
        wxPayload.put("uniID", uniId);
        JsonNode wxRes = restClient.post()
                .uri(authBaseUrl + "/qr-code/wx_code")
                .headers(h -> h.putAll(buildHeaders()))
                .body(wxPayload.toString())
                .retrieve()
                .body(JsonNode.class);
        String wxCode = wxRes == null ? null : wxRes.path("data").path("wxCode").asText(null);
        if (wxCode == null || wxCode.isEmpty()) {
            throw new IOException("换取 wechat_code 失败: " + (wxRes == null ? "空响应" : wxRes.toString()));
        }

        // 2. sign_in 换 token
        ObjectNode signPayload = objectMapper.createObjectNode();
        signPayload.put("from", "web");
        signPayload.put("type", 4);
        signPayload.put("wechat_code", wxCode);
        JsonNode signRes = restClient.post()
                .uri(authBaseUrl + "/sign_in")
                .headers(h -> h.putAll(buildHeaders()))
                .body(signPayload.toString())
                .retrieve()
                .body(JsonNode.class);
        String token = signRes == null ? null : signRes.path("data").path("token").asText(null);
        if (token == null || token.isEmpty()) {
            throw new IOException("sign_in 失败: " + (signRes == null ? "空响应" : signRes.toString()));
        }

        // 3. 写入 token 文件 (pan123_service 读取的格式: {"token":"Bearer xxx"})
        String bearer = token.startsWith("Bearer ") ? token : "Bearer " + token;
        writeTokenFile(bearer);

        // 4. 自动启动 123 云盘服务
        Map<String, Object> startRes = panServiceManager.start("pan123");
        return Map.of(
                "success", true,
                "token", maskToken(token),
                "serviceStarted", startRes.getOrDefault("running", false),
                "startMsg", startRes.getOrDefault("msg", "")
        );
    }

    private void writeTokenFile(String bearer) throws IOException {
        File dir = new File(serviceDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File tokenFile = new File(dir, tokenFileName);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("token", bearer);
        Files.writeString(tokenFile.toPath(), objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
        log.info("123 云盘 token 已写入: {}", tokenFile.getAbsolutePath());
    }

    /** 是否存在有效 token 文件 */
    public boolean tokenFileExists() {
        File f = new File(new File(serviceDir), tokenFileName);
        return f.exists() && f.length() > 0;
    }

    /** 清除 token 文件 (退出登录) */
    public boolean clearTokenFile() {
        File f = new File(new File(serviceDir), tokenFileName);
        return f.delete();
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 12) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }
}
