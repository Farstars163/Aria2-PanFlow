package com.Farstars.org.service;

import com.Farstars.org.service.impl.Aria2Client;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import java.io.IOException;

public class Aria2ClientImpl implements Aria2Client {
    private static final String RPC_URL = "http://localhost:6800/jsonrpc";
    private static final String TOKEN = "token:123456"; // 你的 aria2 密钥
    private static final OkHttpClient client = new OkHttpClient();

    /**
     * 通用发送 RPC 请求
     */
    public JSONObject sendRpc(String method, JSONArray params) throws IOException {
        JSONObject json = new JSONObject();
        json.put("jsonrpc", "2.0");
        json.put("id", "java-client");
        json.put("method", method);

        // 拼接 token + 业务参数
        JSONArray finalParams = new JSONArray();
        finalParams.add(TOKEN);
        if (params != null) {
            finalParams.addAll(params);
        }

        json.put("params", finalParams);

        RequestBody body = RequestBody.create(
                json.toJSONString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(RPC_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Aria2 请求失败：" + response.code());
            }
            String respBody = response.body().string();
            return JSONObject.parseObject(respBody);
        }
    }

    /**
     * 1. 添加下载任务
     */
    public String addUri(String url) throws IOException {
        JSONArray params = new JSONArray();
        JSONArray urls = new JSONArray();
        urls.add(url);
        params.add(urls);

        JSONObject response = sendRpc("aria2.addUri", params);
        return response.getString("result");
    }

    /**
     * 2. 查询单个任务状态
     */
    public JSONObject tellStatus(String gid) throws IOException {
        JSONArray params = new JSONArray();
        params.add(gid);

        JSONArray keys = new JSONArray();
        keys.add("gid");
        keys.add("status");
        keys.add("totalLength");
        keys.add("completedLength");
        keys.add("downloadSpeed");
        keys.add("files");
        params.add(keys);

        JSONObject response = sendRpc("aria2.tellStatus", params);
        return response.getJSONObject("result");
    }

    // ====================== 【你 WebSocket 必须的方法】补全 ======================
    /**
     * 3. 查询所有【正在下载】的任务（给 ProgressWebSocket 用）
     */
    public JSONArray tellActive() throws IOException {
        JSONArray params = new JSONArray();

        // 指定要返回的字段
        JSONArray keys = new JSONArray();
        keys.add("gid");
        keys.add("status");
        keys.add("totalLength");
        keys.add("completedLength");
        keys.add("downloadSpeed");
        keys.add("files");
        params.add(keys);

        JSONObject response = sendRpc("aria2.tellActive", params);
        return response.getJSONArray("result");
    }

    /**
     * 4. 查询所有等待中的任务（可选）
     */
    public JSONArray tellWaiting() throws IOException {
        JSONArray params = new JSONArray();
        // 增加列表查询所需偏移和条数参数
        params.add(0);
        params.add(100);

        JSONArray keys = new JSONArray();
        keys.add("gid");
        keys.add("status");
        keys.add("totalLength");
        keys.add("completedLength");
        keys.add("files");
        params.add(keys);

        JSONObject response = sendRpc("aria2.tellWaiting", params);
        return response.getJSONArray("result");
    }

    /**
     * 5. 暂停任务（可选）
     */
    public void pause(String gid) throws IOException {
        JSONArray params = new JSONArray();
        params.add(gid);
        sendRpc("aria2.pause", params);
    }

    /**
     * 6. 继续任务（可选）
     */
    public void unpause(String gid) throws IOException {
        JSONArray params = new JSONArray();
        params.add(gid);
         sendRpc("aria2.unpause", params);
    }

    /**
     * 7. 移除任务（可选）
     */
    public void remove(String gid) throws IOException {
        JSONArray params = new JSONArray();
        params.add(gid);
        sendRpc("aria2.remove", params);
    }
}