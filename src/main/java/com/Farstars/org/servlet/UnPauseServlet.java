package com.Farstars.org.servlet;

import com.Farstars.org.service.Aria2ClientImpl;
import com.Farstars.org.service.impl.Aria2Client;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/api/unpause")
public class UnPauseServlet extends HttpServlet {
    protected Aria2Client aria2Client = new Aria2ClientImpl();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        request.setCharacterEncoding("UTF-8");

        String gid = request.getParameter("gid");
        JSONObject result = new JSONObject();
        if (gid == null || gid.isBlank()) {
            result.put("code", 400);
            result.put("msg", "Gid不能为空");
            response.getWriter().write(result.toJSONString());
            return;
        }
        try{
            aria2Client.unpause(gid);
            result.put("code", 200);
            result.put("msg","继续下载成功");

    }catch(Exception e){
        e.printStackTrace();
        result.put("code", 500);
        result.put("msg","继续下载失败" + e.getMessage());
        }
        response.getWriter().write(result.toJSONString());
    }

}
