package com.farstars.controller;

import com.farstars.domain.DuPan;
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
@RequestMapping("/api/baidu")
public class DuPanController {

    private static final Logger log = LoggerFactory.getLogger(DuPanController.class);


    private RestClient restClient;
    private Aria2Client aria2Client;
    private ObjectMapper objectMapper;

    @Value("${baidu.pan.user-agent}")
    private String userAgent;
    @Value("${baidu.pan.list-url}")
    private String listUrl;
    @Value("${baidu.pan.meta-url}")
    private String metaUrl;


    public DuPanController(RestClient.Builder restClientbulider, Aria2Client aria2Client, ObjectMapper objectMapper) {
        this.restClient = restClientbulider.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取文件列表
     *
     * @param action
     * @param token
     * @param dir
     * @return
     */
    @GetMapping
    public ResponseEntity<?> listFiles(
            @RequestParam String action,
            @RequestParam String token,
            @RequestParam(required = false, defaultValue = "/") String dir
    ) {
        if (!"list".equals(action)) {
            return ResponseEntity.badRequest().build();
        }

        try {
            JsonNode response = restClient.get()
                    .uri(listUrl, dir, token)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(JsonNode.class);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("调用百度网盘列表API失败:{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> downloadFile(
            @RequestBody DuPan payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "不支持的Action"));
        }

        if (payload.getFilename() == null || payload.getFilename().trim().isEmpty() || payload.getFsId() == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "参数缺失"));
        }

        try {
            String dLink = getBaiduDlink(payload.getFsId(), payload.getToken());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("msg", "解析百度网盘直链失败"));
            }

            String finalDownloadUrl = dLink + "&access_token=" + payload.getToken();
            boolean success = pushToAria2(finalDownloadUrl, payload.getFilename(), payload.getDir());
            return ResponseEntity.ok(Collections.singletonMap("success", success));
        } catch (Exception e) {
            log.error("Aria2推送失败，请检查Aria2是否启动");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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

    private boolean pushToAria2(String finalDownloadUrl, String filename, String dir) throws Exception {
        log.info("准备推送网盘文件 {} 至下载器, 目标目录: {}", filename, dir);

        try {
            String gid = aria2Client.addUri(finalDownloadUrl, dir,filename, List.of("User-Agent: pan.baidu.com"));
            return gid != null && !gid.isEmpty();
        } catch (IOException e) {
            log.error("推送任务到 Aria2 失败", e);
            return false;
        }
    }
}
