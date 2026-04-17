package com.Farstars.org.servlet;

import com.Farstars.org.service.Aria2ClientImpl;
import com.Farstars.org.service.impl.Aria2Client;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/api/remove")
public class RemoveServlet extends HttpServlet {
    protected Aria2Client aria2Client = new Aria2ClientImpl();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        request.setCharacterEncoding("UTF-8");

        String gid = request.getParameter("gid");
        try{
            aria2Client.remove(gid);
    }catch(Exception e){
        e.printStackTrace();

        }
    }
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        this.doPost(request, response);
    }
}
