package com.farstars.controller;

import com.farstars.domain.Quark;
import com.farstars.service.Aria2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quark")
public class QuarkPanController {

    private static final Logger log = LoggerFactory.getLogger(QuarkPanController.class);


    private RestClient restClient;
    private Aria2Client aria2Client;
    private ObjectMapper objectMapper;

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

    public QuarkPanController(RestClient.Builder restClientbulider, Aria2Client aria2Client, ObjectMapper objectMapper) {
        this.restClient = restClientbulider.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取文件列表
     *
     * @param action
     * @param cookie
     * @param dir
     * @return
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
            return ResponseEntity.badRequest().build();
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
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("msg","Cookie已失效，请重新抓取"));
            }
            return ResponseEntity.ok(objectMapper.readTree(response));
        } catch (Exception e) {
            log.error("调用夸克网盘列表API失败:{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> downloadFile(
            @RequestBody Quark payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "不支持的Action"));
        }

        if (payload.getFilename() == null || payload.getFilename().trim().isEmpty() || payload.getFsId() == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "参数缺失"));
        }

        try {
            String dLink = getQuarkDlink(payload.getFsId(), payload.getCookie());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "解析夸克网盘直链失败"));
            }

            boolean success = pushToAria2(dLink, payload.getFilename(), payload.getDir(), payload.getCookie());
            return ResponseEntity.ok(Collections.singletonMap("success", success));
        } catch (Exception e) {
            log.error("Aria2推送失败，请检查Aria2是否启动");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getQuarkDlink(String fsId, String cookie) throws Exception {
        Map<String, Object> payload = Map.of("fids", List.of(fsId.toString()));

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