package com.farstars.controller;

import com.farstars.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 本地文件系统浏览, 用于选择下载目录
 */
@RestController
@RequestMapping("/api/fs")
public class FileSystemController {

    @GetMapping("/list")
    public ResponseEntity<Result> list(@RequestParam(required = false) String path) {
        // 根目录
        if (path == null || path.isEmpty()) {
            List<Map<String, Object>> rootList = new ArrayList<>();
            for (File root : File.listRoots()) {
                if (root.canRead()) {
                    rootList.add(Map.of(
                            "name", root.toString(),
                            "path", root.getAbsolutePath().replace("\\", "/"),
                            "hasChildren", true));
                }
            }
            return ResponseEntity.ok(Result.ok(rootList));
        }

        // 子目录
        File dir = new File(path);
        List<Map<String, Object>> list = new ArrayList<>();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    if (file.isDirectory() && file.canRead()) {
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
                        list.add(Map.of(
                                "name", file.getName(),
                                "path", file.getAbsolutePath().replace("\\", "/"),
                                "hasChildren", hasChildren));
                    }
                }
            }
        }
        return ResponseEntity.ok(Result.ok(list));
    }
}
