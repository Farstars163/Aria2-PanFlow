package com.Farstars.org.servlet;

import com.Farstars.org.service.Aria2ClientImpl;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/api/download")
public class DownloadServlet extends HttpServlet {

    protected Aria2ClientImpl Aria2ClientImpl = new Aria2ClientImpl();
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 设置响应格式为 JSON
        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("UTF-8");
        req.setCharacterEncoding("UTF-8");

        // 获取前端传入的下载链接
        String url = req.getParameter("url");
        JSONObject result = new JSONObject();

        // 参数校验
        if (url == null || url.isBlank()) {
            result.put("code", 400);
            result.put("msg", "下载链接不能为空");
            resp.getWriter().write(result.toJSONString());
            return;
        }

        try {
            // 调用 Aria2 提交下载任务
            String gid = Aria2ClientImpl.addUri(url);

            // 成功返回
            result.put("code", 200);
            result.put("gid", gid);
            result.put("msg", "任务提交成功");
        } catch (Exception e) {
            // 失败返回
            result.put("code", 500);
            result.put("msg", "任务提交失败：" + e.getMessage());
            e.printStackTrace();
        }

        // 输出结果
        resp.getWriter().write(result.toJSONString());
    }
}