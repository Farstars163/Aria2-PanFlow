package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.domain.Mcloud;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中国移动云盘(和彩云) - 直链解析与 Aria2 推送.
 * 参考网盘直链下载助手(v1.1.3.user.js $mcloud)与 AList 139 驱动:
 * - 列表: POST personal-kd-njs.yun.139.com/hcy/file/list {parentFileId} (Authorization 鉴权, 根目录为空)
 * - 直链: POST .../hcy/file/getDownloadUrl {fileId}, 需 Mcloud-Sign 签名(见 buildMcloudSign)
 * - 鉴权: 页面 cookie 中名为 authorization 的值, 直接作为 Authorization 头
 */
@RestController
@RequestMapping("/api/mcloud")
public class McloudPanController {

    private static final Logger log = LoggerFactory.getLogger(McloudPanController.class);

    // 签名随机串字符集(与用户脚本 getRandomString 一致)
    private static final String RANDOM_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
    // 直链请求固定头(与用户脚本一致; 对照 AList 补充 Accept/Origin/Inner-Hcy-Router-Https)
    private static final String[] FIXED_HEADERS = {
            "Caller", "web",
            "CMS-DEVICE", "default",
            "Mcloud-Channel", "1000101",
            "Mcloud-Client", "10701",
            "Mcloud-Version", "7.14.2",
            "X-DeviceInfo", "||9|7.17.0|edge||||windows 10||zh-CN|||",
            "X-Huawei-ChannelSrc", "10000034",
            "X-Inner-Ntwk", "2",
            "X-M4C-Caller", "PC",
            "X-M4C-Src", "10002",
            "X-SvcType", "1",
            "X-Yun-Api-Version", "v1",
            "X-Yun-App-Channel", "10000034",
            "X-Yun-Channel-Source", "10000034",
            "X-Yun-Client-Info", "||9|7.17.0|edge||||windows 10||zh-CN|||||",
            "X-Yun-Module-Type", "100",
            "X-Yun-Svc-Type", "1",
            "X-Yun-Url-Type", "3",
            "Accept", "application/json, text/plain, */*",
            "Origin", "https://yun.139.com",
            "Inner-Hcy-Router-Https", "1"
    };

    private final RestClient restClient;
    private final Aria2Client aria2Client;
    private final ObjectMapper objectMapper;
    private final CdpClient cdpClient;

    @Value("${mcloud.pan.list-url}")
    private String listUrl;
    @Value("${mcloud.pan.get-link-url}")
    private String getLinkUrl;
    @Value("${mcloud.pan.referer}")
    private String referer;
    @Value("${mcloud.pan.user-agent}")
    private String userAgent;

    public McloudPanController(RestClient.Builder restClientBuilder, Aria2Client aria2Client, ObjectMapper objectMapper, CdpClient cdpClient) {
        this.restClient = restClientBuilder.build();
        this.aria2Client = aria2Client;
        this.objectMapper = objectMapper;
        this.cdpClient = cdpClient;
    }

    /**
     * CDP 自动获取移动云盘授权: 读取调试浏览器 yun.139.com 的完整 cookie,
     * 并从中提取 authorization 值(作为后续请求的 Authorization 头).
     */
    @GetMapping("/cdp-cookie")
    public ResponseEntity<?> cdpCookie() {
        try {
            String cookie = cdpClient.getCookies("https://yun.139.com");
            if (cookie.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "未读取到 cookie，请确认已在调试浏览器中登录中国移动云盘"));
            }
            String authorization = extractCookie(cookie, "authorization");
            if (authorization == null || authorization.isEmpty()) {
                return ResponseEntity.ok(Result.error(500, "cookie 中未找到 authorization，请刷新 yun.139.com 页面后重试"));
            }
            return ResponseEntity.ok(Result.ok(Map.of(
                    "authorization", authorization,
                    "cookie", cookie
            )));
        } catch (Exception e) {
            log.error("CDP 读取移动云盘 cookie 失败: {}", e.getMessage());
            return ResponseEntity.ok(Result.error(500, "自动获取失败: " + e.getMessage()));
        }
    }

    /**
     * 获取文件列表
     *
     * @param action        list
     * @param authorization Authorization 头值
     * @param dir           目录 id (空为根目录)
     */
    @GetMapping
    public ResponseEntity<?> listFiles(
            @RequestParam String action,
            @RequestParam String authorization,
            @RequestParam(required = false, defaultValue = "") String dir
    ) {
        if (!"list".equals(action)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }
        try {
            // 根目录 parentFileId 必须为 "/"(AList 文档: 新个人云根目录为 /, 空值会被 API 拒绝)
            String parentFileId = (dir == null || dir.isEmpty()) ? "/" : dir;
            // 列表 body 对齐网页/AList: parentFileId + 分页字段(startNumber 从 1 开始)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentFileId", parentFileId);
            body.put("fileType", 0);
            body.put("sortDirection", 0);
            body.put("startNumber", 1);
            body.put("endNumber", 100);
            String bodyJson = objectMapper.writeValueAsString(body);
            var request = restClient.post()
                    .uri(listUrl)
                    .header("Authorization", normalizeAuthorization(authorization))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("Mcloud-Sign", buildMcloudSign(bodyJson))
                    .header("Referer", referer)
                    .header("User-Agent", userAgent);
            // 列表请求同样携带全套固定头(客户端标识, 缺失会被风控判定认证失败)
            for (int i = 0; i < FIXED_HEADERS.length; i += 2) {
                request = request.header(FIXED_HEADERS[i], FIXED_HEADERS[i + 1]);
            }
            String response = request
                    .body(bodyJson)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> { })
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return ResponseEntity.status(401).body(Result.error(401, "授权已失效，请重新获取"));
            }
            JsonNode node = objectMapper.readTree(response);
            if (!node.path("success").asBoolean(false)) {
                String msg = node.path("message").asText("调用移动云盘列表接口失败");
                return ResponseEntity.status(401).body(Result.error(401, "移动云盘接口返回: " + msg));
            }
            // 响应归一化为标准 {id,name,size,dir} 列表; raw 为原始响应(便于诊断结构)
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", normalizeFileList(node));
            result.put("raw", node);
            return ResponseEntity.ok(Result.ok(result));
        } catch (Exception e) {
            log.error("调用移动云盘列表API失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Result.error(500, "调用移动云盘列表API失败"));
        }
    }

    /**
     * 从移动云盘列表响应中提取并归一化文件数组:
     * 递归查找第一个非空数组(覆盖 fileList/list/contentList/catalogList/items/records
     * 及 data.data 等多层嵌套), 条目字段兼容 fileId/contentId/contentID/id 等.
     */
    private ArrayNode normalizeFileList(JsonNode node) {
        JsonNode arr = findFileArray(node.path("data"), 0);
        if (arr == null) arr = findFileArray(node, 0);
        ArrayNode list = objectMapper.createArrayNode();
        if (arr != null && arr.isArray()) {
            for (JsonNode f : arr) {
                ObjectNode o = objectMapper.createObjectNode();
                o.put("id", firstText(f, "fileId", "contentId", "contentID", "id", "coId", "catalogId"));
                o.put("name", firstText(f, "fileName", "contentName", "name", "coName", "catalogName"));
                o.put("size", firstLong(f, "fileSize", "contentSize", "coSize", "size"));
                o.put("dir", isDirNode(f));
                list.add(o);
            }
        }
        return list;
    }

    /** 递归向下查找第一个非空数组(优先已知字段名, 最多 3 层) */
    private JsonNode findFileArray(JsonNode node, int depth) {
        if (node == null || depth > 3) return null;
        if (node.isArray()) return node;
        for (String k : new String[]{"fileList", "list", "contentList", "catalogList", "items", "records", "folderList"}) {
            JsonNode v = node.path(k);
            if (v.isArray() && v.size() > 0) return v;
            if (v.isObject() && !v.isEmpty()) {
                JsonNode r = findFileArray(v, depth + 1);
                if (r != null) return r;
            }
        }
        // 兜底: 遍历所有对象字段找第一个非空数组 (Jackson 3: properties() 返回 Set)
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode v = e.getValue();
            if (v.isArray() && v.size() > 0) return v;
            if (v.isObject() && !v.isEmpty()) {
                JsonNode r = findFileArray(v, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String firstText(JsonNode f, String... keys) {
        for (String k : keys) {
            JsonNode v = f.path(k);
            if (v.isTextual() && !v.asText().isEmpty()) return v.asText();
            if (v.isNumber()) return v.asText();
        }
        return "";
    }

    private static long firstLong(JsonNode f, String... keys) {
        for (String k : keys) {
            JsonNode v = f.path(k);
            if (v.isNumber()) return v.asLong();
            if (v.isTextual()) {
                try { return Long.parseLong(v.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private static boolean isDirNode(JsonNode f) {
        if (f.path("fileType").asInt(-1) == 0) return true;
        if ("folder".equals(f.path("fileType").asText())) return true;
        if (f.path("dir").asBoolean(false)) return true;
        return f.has("dirEtag") || f.has("caName") || f.has("catalogId") || f.path("isDirectory").asBoolean(false);
    }

    /**
     * 推送到Aria2
     */
    @PostMapping
    public ResponseEntity<?> downloadFile(@RequestBody Mcloud payload) {
        if (!"download".equals(payload.getAction())) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的Action"));
        }
        if (payload.getFileId() == null || payload.getFileId().trim().isEmpty()
                || payload.getAuthorization() == null || payload.getAuthorization().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "参数缺失"));
        }

        try {
            String dLink = getMcloudDlink(payload.getFileId(), payload.getAuthorization());
            if (dLink == null) {
                return ResponseEntity.badRequest().body(Result.error(400, "解析移动云盘直链失败"));
            }
            boolean success = pushToAria2(dLink, payload.getFilename(), payload.getDir());
            return ResponseEntity.ok(Result.ok(Map.of("success", success)));
        } catch (Exception e) {
            log.error("解析移动云盘直链失败: {}", e.getMessage());
            String msg = e.getMessage();
            return ResponseEntity.status(500).body(Result.error(500, msg == null || msg.isEmpty() ? "解析移动云盘直链失败" : msg));
        }
    }

    /**
     * 解析移动云盘文件直链: POST hcy/file/getDownloadUrl {fileId} + Mcloud-Sign 签名
     */
    private String getMcloudDlink(String fileId, String authorization) throws Exception {
        String bodyJson = "{\"fileId\":\"" + fileId + "\"}";
        String mcloudSign = buildMcloudSign(bodyJson);

        var request = restClient.post()
                .uri(getLinkUrl)
                .header("Authorization", normalizeAuthorization(authorization))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Mcloud-Sign", mcloudSign)
                .header("Referer", referer)
                .header("User-Agent", userAgent);
        for (int i = 0; i < FIXED_HEADERS.length; i += 2) {
            request = request.header(FIXED_HEADERS[i], FIXED_HEADERS[i + 1]);
        }
        String response = request
                .body(bodyJson)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> { })
                .body(String.class);

        JsonNode node = objectMapper.readTree(response);
        if (node.path("success").asBoolean(false)) {
            return node.path("data").path("url").asText(null);
        }
        log.error("移动云盘获取直链失败: {}", node.path("message").asText(""));
        return null;
    }

    /**
     * 生成 Mcloud-Sign: time,key,sign
     * sign = MD5( MD5(Base64(UTF8(字符排序后的 encodeURIComponent(去空白JSON)))) + MD5(time+":"+key) ) 转大写
     */
    private String buildMcloudSign(String bodyJson) throws Exception {
        String time = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String key = randomString(16);

        String noSpace = bodyJson.replaceAll("\\s", "");
        String encoded = encodeURIComponent(noSpace);
        char[] chars = encoded.toCharArray();
        Arrays.sort(chars);
        String sorted = new String(chars);

        String a = md5Hex(Base64.getEncoder().encodeToString(sorted.getBytes(StandardCharsets.UTF_8)));
        String l = md5Hex(time + ":" + key);
        String sign = md5Hex(a + l).toUpperCase();
        return time + "," + key + "," + sign;
    }

    /** 与 JS encodeURIComponent 等价(保留 -_.!~*'() 与字母数字) */
    private static String encodeURIComponent(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String randomString(int len) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(RANDOM_CHARS.charAt(r.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 规范化 Authorization: 移动云盘要求 "Basic <值>" 格式(AList 文档说明只填 Basic 后面的内容,
     * cookie 中的 authorization 可能不带 Basic 前缀, 自动补全)
     */
    private static String normalizeAuthorization(String auth) {
        if (auth == null || auth.isEmpty()) return "";
        String t = auth.trim();
        return t.startsWith("Basic ") || t.startsWith("basic ") ? t : "Basic " + t;
    }

    /** 从 "k1=v1; k2=v2" cookie 串中提取指定 name 的值 */
    private static String extractCookie(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int idx = p.indexOf('=');
            if (idx > 0 && p.substring(0, idx).equals(name)) {
                return p.substring(idx + 1);
            }
        }
        return null;
    }

    private boolean pushToAria2(String dlink, String filename, String dir) {
        log.info("准备推送移动云盘文件 {} 至下载器, 目标目录: {}", filename, dir);
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
