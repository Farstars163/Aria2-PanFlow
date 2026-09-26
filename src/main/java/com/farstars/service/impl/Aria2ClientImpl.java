package com.farstars.service.impl;

import com.farstars.service.Aria2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

@Service
public class Aria2ClientImpl implements Aria2Client
{
    private static final Logger log = LoggerFactory.getLogger(Aria2ClientImpl.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;


    @Value("${aria2.rpc-url}")
    private String RPC_URL;
    @Value("${aria2.token}")
    private String TOKEN;

    public Aria2ClientImpl(RestClient.Builder restclientBuilder,ObjectMapper objectMapper) {
        this.restClient = restclientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** 查询任务时携带的公共字段 */
    private static final String[] TASK_KEYS = {
            "gid", "status", "totalLength", "completedLength", "downloadSpeed",
            "dir", "out", "errorCode", "errorMessage", "files",
            "uploadSpeed", "uploadLength", "numSeeders", "bt-seeder", "connections", "bittorrent"
    };


    @Override
    public ObjectNode sendRpc(String method, ArrayNode params) throws IOException {
        ObjectNode reqBody = objectMapper.createObjectNode();
        reqBody.put("jsonrpc", "2.0");
        reqBody.put("id", "java-client");
        reqBody.put("method", method);

        ArrayNode finalParams = objectMapper.createArrayNode();
        finalParams.add("token:" + TOKEN);
        if(params!=null) {
            finalParams.addAll(params);
        }

        reqBody.set("params",finalParams);
try {
    String jsonString = objectMapper.writeValueAsString(reqBody);
    byte[] responseBytes = restClient.post()
            .uri(RPC_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(jsonString)
            .retrieve()
            .body(byte[].class);

    if (responseBytes == null) {
        throw new IOException("Aria2 返回了空内容");
    }
    JsonNode response = objectMapper.readTree(responseBytes);
    if (response.has("error")) {
        throw new IOException(response.get("error").get("message").asText());
    }
    return response.asObject();
}catch (RestClientException e) {
    log.error("请求Aria2服务失败,method:{},msg:{}",method,e.getMessage());
    throw new IOException(e);
}
    }

    /**
     * 添加下载任务
     * @param url
     * @param dir
     * @return
     * @throws IOException
     */
    @Override
    public String addUri(String url, String dir) throws IOException {
        ensureGlobalProxy();
        ArrayNode params = objectMapper.createArrayNode();
        ArrayNode urls = objectMapper.createArrayNode();
        urls.add(url);
        params.add(urls);
        if(dir!=null && !dir.trim().isEmpty()) {
            ObjectNode options = objectMapper.createObjectNode();
            options.put("dir", dir);
            // 文件已存在且无 .aria2 控制文件时 aria2 默认拒绝(防截断), 允许覆盖避免任务报错
            options.put("allow-overwrite", "true");
            params.add(options);
        }
        JsonNode response = sendRpc("aria2.addUri", params);
        return response.get("result").asText();
    }

    @Override
    public ObjectNode tellStatus(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(gid);

        ArrayNode keys = objectMapper.createArrayNode();
        for (String key : TASK_KEYS) {
            keys.add(key);
        }
        params.add(keys);

        JsonNode response = sendRpc("aria2.tellStatus", params);
        return response.get("result").asObject();
    }

    @Override
    public ArrayNode getPeers(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(gid);
        JsonNode response = sendRpc("aria2.getPeers", params);
        return response.get("result").asArray();
    }

    @Override
    public ArrayNode tellActive() throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        ArrayNode keys = objectMapper.createArrayNode();
        for (String key : TASK_KEYS) {
            keys.add(key);
        }
        params.add(keys);
        ObjectNode response = sendRpc("aria2.tellActive", params);
        return response.get("result").asArray();
    }

    @Override
    public ArrayNode tellWaiting() throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(0).add(100);
        ArrayNode keys = objectMapper.createArrayNode();
        for (String key : TASK_KEYS) {
            keys.add(key);
        }
        params.add(keys);
        ObjectNode response = sendRpc("aria2.tellWaiting", params);
        return response.get("result").asArray();
    }

    @Override
    public String pause(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(gid);
        JsonNode response = sendRpc("aria2.pause", params);
        return response.get("result").asText();
    }

    @Override
    public String unpause(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(gid);
        JsonNode response =  sendRpc("aria2.unpause", params);
        return response.get("result").asText();
    }

    @Override
    public String remove(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(gid);
        JsonNode response = sendRpc("aria2.remove", params);
        return response.get("result").asText();
    }

    @Override
    public String removeDownloadResult(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        params.add(gid);
        JsonNode response = sendRpc("aria2.removeDownloadResult", params);
        return response.get("result").asText();
    }

    @Override
    public ArrayNode tellStopped() throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(0).add(100);
        ArrayNode keys = objectMapper.createArrayNode();
        for (String key : TASK_KEYS) {
            keys.add(key);
        }
        params.add(keys);
        ObjectNode response = sendRpc("aria2.tellStopped", params);
        return response.get("result").asArray();
    }

    @Override
    public ObjectNode getGlobalStat() throws IOException {
        return sendRpc("aria2.getGlobalStat", objectMapper.createArrayNode());
    }

    @Override
    public ObjectNode getVersion() throws IOException {
        return sendRpc("aria2.getVersion", objectMapper.createArrayNode());
    }

    /// 代理为全局选项, 不能放在 addUri/addTorrent 的任务级 options 中(会 400 Bad Request),
    /// 需通过 aria2.changeGlobalOption 设置; 代理为空串时清空(不走代理)
    private void ensureGlobalProxy() {
        try {
            String proxy = com.farstars.controller.ProxyController.readProxy();
            ArrayNode params = objectMapper.createArrayNode();
            ObjectNode opts = objectMapper.createObjectNode();
            opts.put("all-proxy", proxy == null ? "" : proxy);
            params.add(opts);
            sendRpc("aria2.changeGlobalOption", params);
        } catch (Exception ignored) { }   // 代理设置失败不影响下载任务提交
    }

    @Override
    public String addUri(String url, String dir, String filename, List<String> headers) throws IOException {
        ensureGlobalProxy();
        ArrayNode urls = objectMapper.createArrayNode().add(url);
        ArrayNode params = objectMapper.createArrayNode().add(urls);

        ObjectNode options = objectMapper.createObjectNode();
        if(filename != null && !filename.trim().isEmpty()) {
            options.put("out", filename);
        }
        if(dir != null && !dir.trim().isEmpty()) {
            options.put("dir", dir);
            // 文件已存在且无 .aria2 控制文件时 aria2 默认拒绝(防截断), 允许覆盖避免任务报错
            options.put("allow-overwrite", "true");
        }
        if(headers != null && !headers.isEmpty()) {
            ArrayNode headerArray = objectMapper.createArrayNode();
            headers.forEach(headerArray::add);
            options.set("header", headerArray);
        }
        params.add(options);

        JsonNode response = sendRpc("aria2.addUri", params);
        return response.get("result").asText();
    }

    @Override
    public String addTorrent(String torrentBase64, String dir, String filename) throws IOException {
        ensureGlobalProxy();
        ArrayNode params = objectMapper.createArrayNode();
        params.add(torrentBase64 == null ? "" : torrentBase64.trim());   // 参数1: torrent base64
        params.add(objectMapper.createArrayNode());                       // 参数2: URI 列表(空数组, aria2 要求 List)
        ObjectNode options = objectMapper.createObjectNode();
        if(filename != null && !filename.trim().isEmpty()) {
            options.put("out", filename);
        }
        if(dir != null && !dir.trim().isEmpty()) {
            options.put("dir", dir);
            // 文件已存在且无 .aria2 控制文件时 aria2 默认拒绝(防截断), 允许覆盖避免任务报错
            options.put("allow-overwrite", "true");
            // 上传已下载过的种子: 先校验已存在文件(完整则直接完成/做种, 避免重新下载)
            options.put("check-integrity", "true");
        }
        params.add(options);   // 参数3: options

        JsonNode response = sendRpc("aria2.addTorrent", params);
        return response.get("result").asText();
    }

}
