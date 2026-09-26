package com.farstars.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 网盘 Python 服务进程管理器 (123云盘 / 阿里云盘)
 *
 * 123 云盘: 需先运行 pan123_login.exe 扫码生成 token, 再启动 pan123_service.exe (端口 5001)
 * 阿里云盘: 启动 aliyun_service.exe, 弹出二维码扫码授权, 授权后监听 5000
 *
 * 默认均不随应用启动, 由前端页面按需触发
 */
@Service
public class PanServiceManager {

    private static final Logger log = LoggerFactory.getLogger(PanServiceManager.class);

    @Value("${pan-service.dir:./python_service}")
    private String serviceDir;

    @Value("${pan-service.aliyun-port:5000}")
    private int aliyunPort;

    @Value("${pan-service.pan123-port:5001}")
    private int pan123Port;

    @Value("${pan-service.aliyun-exe:aliyun_service.exe}")
    private String aliyunExe;

    @Value("${pan-service.pan123-exe:pan123_service.exe}")
    private String pan123Exe;

    @Value("${pan-service.pan123-login-exe:pan123_login.exe}")
    private String pan123LoginExe;

    /** 已启动的进程句柄 (用于优雅停止) */
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    /** 最近一次启动的日志文件 */
    private final Map<String, String> lastLogs = new ConcurrentHashMap<>();

    /** 各服务最近一次启动时间戳(用于定位二维码文件) */
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    /** 123 云盘手动停止标记: 停止后即使有 token 也显示未运行(内存态, 重启后端自动复位) */
    private final java.util.concurrent.atomic.AtomicBoolean pan123Stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 123 API 服务(用于验证 token 有效性) */
    @org.springframework.beans.factory.annotation.Autowired
    private Pan123ApiService pan123ApiService;

    /* ---------------- 状态 ---------------- */

    /**
     * 服务状态
     */
    public Map<String, Object> status(String service) {
        return switch (service) {
            case "aliyun" -> Map.of(
                    "service", "aliyun",
                    "name", "阿里云盘",
                    "type", "service",
                    "running", isPortOpen(aliyunPort),
                    "processAlive", isProcessAlive(aliyunExe),
                    "port", aliyunPort,
                    "authorized", isPortOpen(aliyunPort),
                    "tokenExists", aliyunTokenExists(),
                    "defaultOff", true
            );
            case "pan123" -> Map.of(
                    "service", "pan123",
                    "name", "123云盘",
                    "type", "service",
                    // 123 已改为后端 Java 直连官方 API; running = token 有效且未被手动停止
                    "running", pan123ApiService.validateToken() && !pan123Stopped.get(),
                    "processAlive", false,
                    "port", pan123Port,
                    "authorized", pan123ApiService.validateToken(),
                    "defaultOff", false
            );
            default -> Map.of("service", service, "error", "未知服务: " + service);
        };
    }

    public Map<String, Object> statusAll() {
        return Map.of(
                "aliyun", status("aliyun"),
                "pan123", status("pan123")
        );
    }

    /* ---------------- 启动 ---------------- */

    /**
     * 启动服务. 123 云盘无 token 时先拉起登录程序.
     */
    public Map<String, Object> start(String service) throws IOException {
        return switch (service) {
            case "aliyun" -> startAliyun();
            case "pan123" -> startPan123();
            default -> Map.of("success", false, "msg", "未知服务: " + service);
        };
    }

    private Map<String, Object> startAliyun() throws IOException {
        if (isPortOpen(aliyunPort)) {
            return Map.of("success", true, "msg", "阿里云盘服务已在运行", "running", true);
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            File exe = resolveExe(aliyunExe);
            if (exe == null) {
                return Map.of("success", false, "msg", "未找到 " + aliyunExe + "，请确认 python_service 目录完整");
            }
            // Windows: PyInstaller 程序 stdout 为管道/文件时打印 Emoji 会 GBK 崩溃(已实测),
            // 用 VBS 隐藏静默启动(ShellExecute 不继承句柄, 不崩溃且无窗口)
            launchSilent(exe, "aliyun");
        } else {
            // macOS: 使用系统 python3 运行脚本 (依赖 aligo/flask/flask_cors/requests)
            File script = new File(new File(serviceDir), "aliyun_service.py");
            if (!script.exists()) {
                return Map.of("success", false, "msg", "未找到 aliyun_service.py，请确认 python_service 目录完整");
            }
            launchPythonScript(script, "aliyun");
        }
        // 根据是否已有本地 token 返回不同提示(有 token 无需扫码, 与前端状态同步)
        boolean hasToken = aliyunTokenExists();
        return Map.of("success", true,
                "msg", hasToken
                        ? "阿里云盘服务已启动（已读取本地登录凭证）"
                        : "阿里云盘服务已启动，请使用阿里云盘 App 扫描下方二维码授权",
                "running", false);
    }

    private Map<String, Object> startPan123() throws IOException {
        if (!pan123ApiService.validateToken()) {
            // 无有效 token: 不弹窗, 前端走网页内扫码流程(Pan123AuthService 对接官方 API)
            return Map.of("success", true,
                    "msg", "未检测到有效授权 Token，请使用网页内二维码扫码登录",
                    "loginRequired", true);
        }
        // 123 云盘已由后端 Java 直连官方 API(www.123pan.cn), 无需本地 Python 服务进程
        pan123Stopped.set(false);   // 启动清除手动停止标记
        return Map.of("success", true, "msg", "123云盘已通过后端直连官方 API 就绪", "running", true);
    }

    /**
     * VBS 隐藏静默启动: 用 cscript 执行 WScript.Shell.Run(cmd, 0, False).
     * ShellExecute 创建的进程不继承调用方标准句柄, 可避免 PyInstaller 程序
     * 打印 Emoji 时的 GBK 编码崩溃, 且完全无窗口.
     */
    private void launchSilent(File exe, String key) {
        try {
            File dir = new File(serviceDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File vbs = new File(dir, "hidden_run_" + key + ".vbs");
            String script = "Set objShell = WScript.CreateObject(\"WScript.Shell\")\n"
                    + "If WScript.Arguments.Count >= 2 Then\n"
                    + "  objShell.CurrentDirectory = WScript.Arguments(0)\n"
                    + "  objShell.Run WScript.Arguments(1), 0, False\n"
                    + "End If\n";
            Files.writeString(vbs.toPath(), script, java.nio.charset.StandardCharsets.UTF_8);

            List<String> cmd = List.of(
                    "cscript", "//nologo", vbs.getAbsolutePath(),
                    dir.getAbsolutePath(), exe.getName()
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.start();
            startTimes.put(key, System.currentTimeMillis());
            log.info("已静默启动 {} (VBS)", exe.getName());
        } catch (IOException e) {
            log.error("静默启动 {} 失败: {}", exe.getName(), e.getMessage());
        }
    }

    /**
     * macOS 启动 Python 脚本: python3 aliyun_service.py (工作目录 python_service).
     * 依赖 aligo / flask / flask_cors / requests, 需系统已安装.
     */
    private void launchPythonScript(File script, String key) {
        try {
            File dir = new File(serviceDir);
            List<String> cmd = List.of(
                    "python3", script.getAbsolutePath()
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.start();
            startTimes.put(key, System.currentTimeMillis());
            log.info("已启动 {} (python3)", script.getName());
        } catch (IOException e) {
            log.error("python3 启动 {} 失败: {}", script.getName(), e.getMessage());
        }
    }

    /* ---------------- 停止 ---------------- */

    public Map<String, Object> stop(String service) throws IOException {
        // pan123 为后端 Java 直连官方 API(无本地服务进程); 停止仅置手动停止标记,
        // 保留本地 token, 避免误触停止后需重新扫码
        if ("pan123".equals(service)) {
            pan123Stopped.set(true);
            return Map.of("success", true, "msg", "123云盘已停止(token 已保留, 重新启动即可用)");
        }
        String exeName = switch (service) {
            case "aliyun" -> aliyunExe;
            default -> null;
        };
        if (exeName == null) {
            return Map.of("success", false, "msg", "未知服务: " + service);
        }
        // 优雅停止由本管理器启动的进程
        Process p = processes.remove(service);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 兜底: 按镜像名结束 (Windows)
        killByImage(exeName);
        return Map.of("success", true, "msg", service + " 服务已停止");
    }

    /** 删除 123 云盘 token 配置文件 (退出登录) */
    private boolean clearPan123TokenFiles() {
        File dir = new File(serviceDir);
        if (!dir.exists() || !dir.isDirectory()) return false;
        boolean any = false;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") || n.contains("token"));
        if (files != null) {
            for (File f : files) {
                if (f.delete()) any = true;
            }
        }
        return any;
    }

    /** 阿里云盘是否已有本地 token (.aligo/aliyun_aria2.json) */
    private boolean aliyunTokenExists() {
        try {
            File f = new File(new File(serviceDir, ".aligo"), "aliyun_aria2.json");
            return f.exists() && f.length() > 10;
        } catch (Exception e) {
            return false;
        }
    }

    /** 123 云盘是否被手动停止(供 Pan123Controller 拒绝列表/下载访问) */
    public boolean isPan123Stopped() {
        return pan123Stopped.get();
    }

    public Map<String, Object> stopAll() throws IOException {
        stop("aliyun");
        stop("pan123");
        return Map.of("success", true, "msg", "网盘服务已全部停止");
    }

    /* ---------------- 内部工具 ---------------- */

    private File resolveExe(String name) {
        File dir = new File(serviceDir);
        File exe = new File(dir, name);
        return exe.exists() ? exe : null;
    }

    private Process launch(File exe, List<String> args, String key) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.getAbsolutePath());
        cmd.addAll(args.subList(1, args.size()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(serviceDir));

        // Python 程序输出 ASCII 二维码时, Windows 默认 GBK 编码会因 \xa0 崩溃;
        // 强制 UTF-8 输出
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");

        // 关键: PyInstaller 打包的 Python 程序在 stdout 重定向到文件/管道时,
        // 打印 Emoji 等字符会触发 GBK 编码崩溃(已实测 pan123_service 启动即崩).
        // 因此 stdout 保持继承(不重定向), 仅 stderr 落盘供排错.
        File logFile = new File(serviceDir, key + ".log");
        try {
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        } catch (Exception e) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        lastLogs.put(key, logFile.getAbsolutePath());
        startTimes.put(key, System.currentTimeMillis());
        Process p = pb.start();
        processes.put(key, p);
        return p;
    }

    private boolean tokenExists() {
        File dir = new File(serviceDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json") || n.contains("token"));
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.length() > 0) return true;
        }
        return false;
    }

    private boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isProcessAlive(String imageName) {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + imageName)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains(imageName) && !out.contains("没有运行的任务");
        } catch (Exception e) {
            return false;
        }
    }

    private void killByImage(String imageName) {
        try {
            new ProcessBuilder("taskkill", "/F", "/IM", imageName).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("taskkill {} 失败: {}", imageName, e.getMessage());
        }
    }

    /* ---------------- 扫码登录二维码 ---------------- */

    /**
     * 获取扫码登录二维码.
     *
     * aliyun: 服务启动后会在系统 TEMP 目录生成 PNG 二维码图片(并用图片查看器打开),
     *         此处扫描启动时间戳之后最新的图片文件, 转 base64 返回.
     * pan123: pan123_login.exe 在 stdout 打印 ASCII 二维码, 捕获其日志文本返回.
     */
    public Map<String, Object> qrcode(String service) {
        return switch (service) {
            case "aliyun" -> aliyunQrcode();
            case "pan123" -> pan123Qrcode();
            default -> Map.of("success", false, "msg", "未知服务: " + service);
        };
    }

    private Map<String, Object> aliyunQrcode() {
        Long start = startTimes.get("aliyun");
        long since = start == null ? System.currentTimeMillis() - 120_000 : start;
        try {
            String tmpDir = System.getProperty("java.io.tmpdir");
            File dir = new File(tmpDir);
            File[] candidates = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".bmp") || lower.endsWith(".gif"))
                        && new File(d, name).lastModified() >= since - 30_000;
            });
            if (candidates == null || candidates.length == 0) {
                return Map.of("success", false, "msg", "二维码尚未生成，请稍候…", "available", false);
            }
            // 取最新一张
            File newest = null;
            for (File f : candidates) {
                if (newest == null || f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
            if (newest == null || newest.length() == 0) {
                return Map.of("success", false, "msg", "二维码尚未生成，请稍候…", "available", false);
            }
            String base64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(newest.toPath()));
            return Map.of("success", true, "available", true,
                    "mime", "image/png", "base64", base64, "file", newest.getName(),
                    "updatedAt", newest.lastModified());
        } catch (IOException e) {
            return Map.of("success", false, "msg", "读取二维码失败: " + e.getMessage());
        }
    }

    private Map<String, Object> pan123Qrcode() {
        // 123 登录程序在独立控制台窗口运行(重定向会崩溃), 二维码需在窗口中查看
        String logPath = lastLogs.get("pan123-login");
        if (logPath == null) {
            if (startTimes.containsKey("pan123-login")) {
                return Map.of("success", true, "available", false,
                        "msg", "扫码窗口已弹出，请用微信扫描窗口内二维码完成授权，完成后再次点击「启动服务」");
            }
            return Map.of("success", false, "msg", "扫码登录程序未启动", "available", false);
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(logPath));
            if (bytes.length == 0) {
                return Map.of("success", false, "msg", "二维码尚未生成，请稍候…", "available", false);
            }
            // 容错解码: 优先 UTF-8, 失败回退 GBK (Windows 控制台程序常见输出编码)
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (content.contains("\uFFFD")) {
                content = new String(bytes, java.nio.charset.Charset.forName("GBK"));
            }
            if (content.isBlank()) {
                return Map.of("success", false, "msg", "二维码尚未生成，请稍候…", "available", false);
            }
            return Map.of("success", true, "available", true, "type", "text", "content", content);
        } catch (IOException e) {
            return Map.of("success", false, "msg", "读取二维码失败: " + e.getMessage());
        }
    }

    /** 工作目录(供前端展示/日志读取) */
    public String getServiceDir() {
        return new File(serviceDir).getAbsolutePath();
    }
}
