package com.Farstars.org.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;

public interface Aria2Client {

    JSONObject sendRpc(String method, JSONArray params) throws IOException;

    String addUri(String url) throws IOException;

    JSONObject tellStatus(String gid) throws IOException;

    JSONArray tellActive() throws IOException;

    JSONArray tellWaiting() throws IOException;

    void pause(String gid) throws IOException;

    void unpause(String gid) throws IOException;

    void remove(String gid) throws IOException;

}
