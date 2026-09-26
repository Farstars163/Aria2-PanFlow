package com.farstars.controller;

import com.farstars.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 阿里云盘服务代理 (Python aliyun_service, 默认端口 5000)
 */
@RestController
@RequestMapping("/api/aliyun")
public class AliyunController {

    private static final Logger log = LoggerFactory.getLogger(AliyunController.class);

    private final RestClient restClient;

    @Value("${aliyun.base-url:http://127.0.0.1:5000/api/aliyun}")
    private String baseUrl;

    public AliyunController(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * 文件列表 (dir: root 或 file_id)
     */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "root") String dir) {
        try {
            JsonNode body = restClient.get()
                    .uri(baseUrl + "?action=list&dir=" + dir)
                    .retrieve()
                    .body(JsonNode.class);
            return ResponseEntity.ok(Result.ok(body));
        } catch (Exception e) {
            log.error("阿里云盘服务不可用: {}", e.getMessage());
            return ResponseEntity.status(502).body(Result.error(502, "阿里云盘服务未启动"));
        }
    }

    /**
     * 推送到 Aria2
     */
    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody Map<String, Object> payload) {
        try {
            JsonNode body = restClient.post()
                    .uri(baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            return ResponseEntity.ok(Result.ok(body));
        } catch (Exception e) {
            log.error("阿里云盘推送失败: {}", e.getMessage());
            return ResponseEntity.status(502).body(Result.error(502, "阿里云盘服务未启动"));
        }
    }
}
