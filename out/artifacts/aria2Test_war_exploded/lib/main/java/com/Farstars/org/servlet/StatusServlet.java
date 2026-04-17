package com.Farstars.org.servlet;

import com.Farstars.org.service.Aria2ClientImpl;
import com.Farstars.org.service.impl.Aria2Client;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/api/status")
public class StatusServlet extends HttpServlet {
    protected Aria2Client aria2Client = new Aria2ClientImpl();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("UTF-8");
        JSONObject result = new JSONObject();

        // 获取 GID 参数
        String gid = req.getParameter("gid");
        if (gid == null || gid.isBlank()) {
            result.put("code", 400);
            result.put("msg", "GID 不能为空");
            resp.getWriter().write(result.toJSONString());
            return;
        }

        try {
            // 查询 Aria2 任务状态
            JSONObject status = aria2Client.tellStatus(gid);
            if (status == null) {
                result.put("code", 404);
                result.put("msg", "任务不存在");
                resp.getWriter().write(result.toJSONString());
                return;
            }

            // 构造前端需要的返回数据
            JSONObject data = new JSONObject();
            data.put("gid", gid);
            data.put("status", status.getString("status"));

            // 安全读取数值字段
            data.put("total", getLongOrDefault(status, "totalLength", 0L));
            data.put("completed", getLongOrDefault(status, "completedLength", 0L));
            data.put("speed", getLongOrDefault(status, "downloadSpeed", 0L));

            // 安全解析文件名
            String fileName = "未知文件";
            if (status.containsKey("files")) {
                com.alibaba.fastjson.JSONArray files = status.getJSONArray("files");
                if (files != null && !files.isEmpty()) {
                    JSONObject file = files.getJSONObject(0);
                    String path = file.getString("path");
                    if (path != null && !path.isEmpty()) {
                        fileName = path.substring(Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\")) + 1);
                        if (fileName.isEmpty()) fileName = path;
                    }
                }
            }
            data.put("fileName", fileName);

            result.put("code", 200);
            result.put("data", data);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "查询失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace();
        }

        resp.getWriter().write(result.toJSONString());
    }

    private long getLongOrDefault(JSONObject json, String key, long defaultValue) {
        try {
            String val = json.getString(key);
            return (val != null && !val.isBlank()) ? Long.parseLong(val) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}