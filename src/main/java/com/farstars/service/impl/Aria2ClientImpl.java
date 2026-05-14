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
        ArrayNode params = objectMapper.createArrayNode();
        ArrayNode urls = objectMapper.createArrayNode();
        urls.add(url);
        params.add(urls);
        if(dir!=null && !dir.trim().isEmpty()) {
            ObjectNode options = objectMapper.createObjectNode();
            options.put("dir", dir);
            params.add(options);
        }
        JsonNode response = sendRpc("aria2.addUri", params);
        return response.get("result").asText();
    }

    @Override
    public ObjectNode tellStatus(String gid) throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(gid);

        ArrayNode keys = objectMapper.createArrayNode()
        .add("gid")
        .add("status")
        .add("totalLength")
        .add("completedLength")
        .add("downloadSpeed")
        .add("files");
        params.add(keys);

        JsonNode response = sendRpc("aria2.tellStatus", params);
        return response.get("result").asObject();
    }

    @Override
    public ArrayNode tellActive() throws IOException {
        ArrayNode params = objectMapper.createArrayNode();
        ArrayNode keys = objectMapper.createArrayNode()
       .add("gid")
       .add("status")
       .add("totalLength")
       .add("completedLength")
       .add("downloadSpeed")
       .add("files");
        params.add(keys);
        ObjectNode response = sendRpc("aria2.tellActive", params);
        return response.get("result").asArray();
    }

    @Override
    public ArrayNode tellWaiting() throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(0).add(100);
        ArrayNode keys = objectMapper.createArrayNode()
        .add("gid")
        .add("status")
        .add("totalLength")
        .add("completedLength")
        .add("files");
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
    public ArrayNode tellStopped() throws IOException {
        ArrayNode params = objectMapper.createArrayNode().add(0).add(100);
        ArrayNode keys = objectMapper.createArrayNode()
        .add("gid")
        .add("status")
        .add("totalLength")
        .add("completedLength")
        .add("files");
        params.add(keys);
        ObjectNode response = sendRpc("aria2.tellStopped", params);
        return response.get("result").asArray();
    }

    @Override
    public ObjectNode getGlobalStat() throws IOException {
        return sendRpc("aria2.getGlobalStat", objectMapper.createArrayNode());
    }

    @Override
    public String addUri(String url, String dir, String filename, List<String> headers) throws IOException {
        ArrayNode urls = objectMapper.createArrayNode().add(url);
        ArrayNode params = objectMapper.createArrayNode().add(urls);

        ObjectNode options = objectMapper.createObjectNode();
        if(filename != null && !filename.trim().isEmpty()) {
            options.put("out", filename);
        }
        if(dir != null && !dir.trim().isEmpty()) {
            options.put("dir", dir);
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

}