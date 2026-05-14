package com.farstars.controller;

import com.farstars.service.Aria2Client;
import com.farstars.util.throwExecution;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import java.io.File;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Aria2ActionController {


    @Autowired
    private Aria2Client aria2Client;

    @PostMapping(value = "/download")
    public ResponseEntity<Map<String, Object>> download(
            @RequestParam String url,
            @RequestParam(required = false) String dir) {
        Map<String, Object> result = new HashMap<>();

        if (url == null || url.isBlank()) {
            result.put("code", 400);
            result.put("msg", "下载链接不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            String gid = aria2Client.addUri(url, dir);

            // 成功返回
            result.put("code", 200);
            result.put("gid", gid);
            result.put("urls", new String[]{url});
            if (dir != null && !dir.isEmpty()) {
                result.put("dir", dir);
            }
            result.put("msg", "任务提交成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 失败返回
            result.put("code", 500);
            result.put("msg", "任务提交失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @SneakyThrows
    @PostMapping(value = "/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @RequestParam String gid) {
        Map<String, Object> result = new HashMap<>();
        return handleAria2Action(gid,"任务暂停成功",aria2Client::pause);

    }

    @SneakyThrows
    @PostMapping(value = "/remove")
    public ResponseEntity<Map<String, Object>> remove(
            @RequestParam String gid) {
        return handleAria2Action(gid,"任务移除成功",aria2Client::remove);
    }
    @SneakyThrows
    @PostMapping(value = "/unpause")
    public ResponseEntity<Map<String, Object>> unpause(
            @RequestParam String gid) throws IOException {
        Map<String, Object> result = new HashMap<>();

        return handleAria2Action(gid,"任务继续下载",aria2Client::unpause);

    }

    @GetMapping(value = "/fs/list")
    public ResponseEntity<List<Map<String, Object>>> FileSystemList(
            @RequestParam(required = false) String path) {
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
            return ResponseEntity.ok(rootList);
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
        return ResponseEntity.ok(list);
    }
    private ResponseEntity<Map<String, Object>> buildErrorResponse(int code, String msg) {
        return ResponseEntity.status(code).body(Map.of("code", code, "msg", msg));
    }

    private ResponseEntity<Map<String, Object>> handleAria2Action(String gid, String msg, throwExecution action){
        if (gid == null || gid.isBlank()) {
            return buildErrorResponse(400,"Gid不能为空");
        }
        try {
            String resultGid = action.apply(gid);
            return ResponseEntity.ok(Map.of("code",200,"gid",resultGid,"msg",msg));

        } catch (Exception e) {
            return buildErrorResponse(500,"操作失败" + e.getMessage());
        }
    }

}

