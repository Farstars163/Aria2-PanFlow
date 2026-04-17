package com.Farstars.org.servlet;

import com.google.gson.Gson;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.util.*;

@WebServlet("/api/fs/list")
public class FileSystemServlet extends HttpServlet {

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getParameter("path");

        resp.setContentType("application/json;charset=utf-8");

        // ===== 1. 根目录 =====
        if (path == null || path.isEmpty()) {
            File[] roots = File.listRoots();
            List<Map<String, Object>> rootList = new ArrayList<>();

            for (File root : roots) {
                if (root.canRead()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", root.toString());
                    map.put("path", root.getAbsolutePath().replace("\\", "/"));
                    map.put("hasChildren", true);
                    rootList.add(map);
                }
            }

            resp.getWriter().write(new Gson().toJson(rootList));
            return;
        }

        // ===== 2. 子目录 =====
        File dir = new File(path);
        List<Map<String, Object>> list = new ArrayList<>();

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();

            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    // 只展示文件夹
                    if (file.isDirectory() && file.canRead()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", file.getName());
                        map.put("path", file.getAbsolutePath().replace("\\", "/"));

                        // 是否有子目录（用于懒加载）
                        File[] children = file.listFiles();
                        boolean hasChildren = false;
                        if (children != null) {
                            for (File child : children) {
                                if (child.isDirectory()) {
                                    hasChildren = true;
                                    break;
                                }
                            }
                        }
                        map.put("hasChildren", hasChildren);
                        list.add(map);
                    }
                }
            }
        }

        resp.getWriter().write(new Gson().toJson(list));
    }
}
