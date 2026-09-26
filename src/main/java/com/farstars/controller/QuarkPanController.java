package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.domain.Quark;
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

@RestController
@RequestMapping("/api/quark")
public class QuarkPanController {

    private static final Logger log = LoggerFactory.getLogger(QuarkPanController.class);

    private final RestClient restClient;
    private final Aria2Client aria2Client;
    private final ObjectMapper objectMapper;
    private final CdpClient cdpClient;

    @Value("${quark.pan.QUARK_UA}")
    private String userAgent;
    @Value("${quark.pan.WEB_UA}")
    private String WEB_UA;
    @Value("${quark.pan.list-url}")
    private String listUrl;
    @Value("${quark.pan.Referer}")
    private String referer;
    @Value("${quark.pan.meta-url}")
    private String metaUrl;

    public QuarkPanController(RestClient.Builder restClientBuilder, Aria2Client aria2Client, ObjectMapper objectMapper, CdpClient cdpClient) {
        this.restClient = restClientBuilder.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
        this.cdpClient = cdpClient;
    }

    /**
     * CDP 自动获取夸克 cookie(含 HttpOnly): 连接浏览器调试端口(9222)读取, 免手动复制.
     */
    @GetMapping("/cdp-cookie")
    public ResponseEntity<?> cdpCookie() {
        try {
            String cookie = cdpClient.getCookies("https://pan.quark.cn");
            if (cookie.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "未读取到 cookie，请确认已在调试浏览器中登录夸克网盘"));
            }
            return ResponseEntity.ok(Result.ok(cookie));
        } catch (Exception e) {
            log.error("CDP 读取夸克 cookie 失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "自动获取失败: " + e.getMessage()));
        }
    }

    /**
     * 获取文件列表
     *
     * @param action list
     * @param cookie 夸克网盘 Cookie
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
            String response = restClient.get()
                    .uri(listUrl, dir)
                    .header("User-Agent", WEB_UA)
                    .header("Cookie", cookie)
                    .header("Referer", referer)
                    .retrieve()
                    .body(String.class);

            if(response != null && response.startsWith("AATF")){
                return ResponseEntity.status(401).body(Result.error(401, "Cookie已失效，请重新抓取"));
            }
            return ResponseEntity.ok(Result.ok(objectMapper.readTree(response)));
        } catch (Exception e) {
            log.error("调用夸克网盘列表API失败:{}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "调用夸克网盘列表API失败"));
        }
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<?> downloadFile(@RequestBody Quark payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }

        if (payload.getFilename() == null || payload.getFilename().trim().isEmpty() || payload.getFsId() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失"));
        }

        try {
            String dLink = getQuarkDlink(payload.getFsId(), payload.getCookie());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Result.error(400, "解析夸克网盘直链失败"));
            }

            boolean success = pushToAria2(dLink, payload.getFilename(), payload.getDir(), payload.getCookie());
            return ResponseEntity.ok(Result.ok(Map.of("success", success)));
        } catch (Exception e) {
            log.error("Aria2推送失败，请检查Aria2是否启动");
            return ResponseEntity.status(500).body(Result.error(500, "Aria2推送失败，请检查Aria2是否启动"));
        }
    }

    private String getQuarkDlink(String fsId, String cookie) throws Exception {
        Map<String, Object> payload = Map.of("fids", List.of(fsId));

        String response = restClient.post()
                .uri(metaUrl)
                .header("Cookie", cookie)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .body(payload)
                .retrieve()
                .body(String.class);

        if (response != null && response.startsWith("AATF")) {
            log.error("Cookie 已失效，请重新抓取");
            return null;
        }

        JsonNode node = objectMapper.readTree(response);
        if (node.path("code").asInt() == 0) {
            return node.path("data").get(0).path("download_url").asText();
        }
        return null;
    }

    private boolean pushToAria2(String dlink, String filename, String dir, String cookie) throws Exception {
        log.info("准备推送网盘文件 {} 至下载器, 目标目录: {}", filename, dir);

        try {
            String gid = aria2Client.addUri(dlink, dir, filename, List.of("User-Agent: " + userAgent, "Referer:" + referer, "Cookie:" + cookie));
            return gid != null && !gid.isEmpty();
        } catch (IOException e) {
            log.error("推送任务到 Aria2 失败", e);
            return false;
        }
    }
}
