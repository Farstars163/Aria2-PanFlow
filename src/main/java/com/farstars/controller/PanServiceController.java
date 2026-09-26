package com.farstars.controller;

import com.farstars.common.Result;
import com.farstars.service.Pan123AuthService;
import com.farstars.service.PanServiceManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 网盘服务进程管理 (123云盘 / 阿里云盘) 与授权引导
 */
@RestController
@RequestMapping("/api/pan-service")
public class PanServiceController {

    private final PanServiceManager panServiceManager;
    private final Pan123AuthService pan123AuthService;

    public PanServiceController(PanServiceManager panServiceManager, Pan123AuthService pan123AuthService) {
        this.panServiceManager = panServiceManager;
        this.pan123AuthService = pan123AuthService;
    }

    /** 各网盘快捷授权链接 */
    @GetMapping("/auth-links")
    public ResponseEntity<Result> authLinks() {
        Map<String, Object> links = Map.of(
                "baidu", Map.of(
                        "url", "https://openapi.baidu.com/oauth/2.0/authorize?response_type=token&scope=basic,netdisk&redirect_uri=oob",
                        "title", "百度网盘",
                        "authType", "web",
                        "desc", "点击授权跳转百度登录，授权成功后复制地址栏 access_token 填入页面",
                        "credentialKey", "token"
                ),
                "quark", Map.of(
                        "url", "https://pan.quark.cn/",
                        "title", "夸克网盘",
                        "authType", "web",
                        "desc", "",
                        "credentialKey", "cookie"
                ),
                "pan123", Map.of(
                        "url", "https://www.123pan.com/",
                        "title", "123云盘",
                        "authType", "qr",
                        "desc", "无需官网授权：点击「启动服务」，后端拉起扫码登录，网页内直接显示二维码，用微信扫码即完成授权",
                        "credentialKey", "token"
                ),
                "aliyun", Map.of(
                        "url", "https://www.aliyundrive.com/",
                        "title", "阿里云盘",
                        "authType", "qr",
                        "desc", "无需官网授权：点击「启动服务」，二维码直接显示在网页内，用阿里云盘 App 扫码即完成授权 (Aligo 接管)",
                        "credentialKey", "token"
                )
        );
        return ResponseEntity.ok(Result.ok(Map.of(
                "links", links,
                "serviceDir", panServiceManager.getServiceDir()
        )));
    }

    /** 获取扫码登录二维码 (aliyun: 图片base64; pan123: ASCII文本) */
    @GetMapping("/qrcode/{service}")
    public ResponseEntity<Result> qrcode(@PathVariable String service) {
        return ResponseEntity.ok(Result.ok(panServiceManager.qrcode(service)));
    }

    /* ---------------- 123 云盘网页内扫码登录 (对接官方 API) ---------------- */

    /** 第一步: 生成二维码数据 {uniID, qrText}, 前端渲染二维码图片 */
    @PostMapping("/pan123/qr-generate")
    public ResponseEntity<Result> pan123GenerateQr() {
        try {
            return ResponseEntity.ok(Result.ok(pan123AuthService.generateQr()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "获取二维码失败: " + e.getMessage()));
        }
    }

    /** 第二步: 轮询扫码状态 {loginStatus, scanPlatform} */
    @GetMapping("/pan123/qr-status")
    public ResponseEntity<Result> pan123QrStatus(@RequestParam String uniID) {
        try {
            return ResponseEntity.ok(Result.ok(pan123AuthService.pollStatus(uniID)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "查询扫码状态失败: " + e.getMessage()));
        }
    }

    /** 第三步: 扫码确认后换取 token, 写文件并自动启动服务 */
    @PostMapping("/pan123/qr-confirm")
    public ResponseEntity<Result> pan123QrConfirm(@RequestParam String uniID) {
        try {
            return ResponseEntity.ok(Result.ok(pan123AuthService.confirmLogin(uniID)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "登录确认失败: " + e.getMessage()));
        }
    }

    /** 123 云盘 token 文件是否存在 */
    @GetMapping("/pan123/token-status")
    public ResponseEntity<Result> pan123TokenStatus() {
        return ResponseEntity.ok(Result.ok(Map.of("tokenExists", pan123AuthService.tokenFileExists())));
    }

    /** 清除 123 云盘 token 文件 (退出登录) */
    @PostMapping("/pan123/token-clear")
    public ResponseEntity<Result> pan123TokenClear() {
        return ResponseEntity.ok(Result.ok(Map.of("cleared", pan123AuthService.clearTokenFile())));
    }

    /** 全部服务状态 */
    @GetMapping("/status")
    public ResponseEntity<Result> statusAll() {
        return ResponseEntity.ok(Result.ok(panServiceManager.statusAll()));
    }

    /** 单个服务状态 */
    @GetMapping("/status/{service}")
    public ResponseEntity<Result> status(@PathVariable String service) {
        return ResponseEntity.ok(Result.ok(panServiceManager.status(service)));
    }

    /** 启动服务 (123 云盘未授权时自动拉起扫码登录) */
    @PostMapping("/start/{service}")
    public ResponseEntity<Result> start(@PathVariable String service) {
        try {
            return ResponseEntity.ok(Result.ok(panServiceManager.start(service)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "启动失败: " + e.getMessage()));
        }
    }

    /** 停止服务 */
    @PostMapping("/stop/{service}")
    public ResponseEntity<Result> stop(@PathVariable String service) {
        try {
            return ResponseEntity.ok(Result.ok(panServiceManager.stop(service)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "停止失败: " + e.getMessage()));
        }
    }

    /** 停止全部网盘服务 */
    @PostMapping("/stop-all")
    public ResponseEntity<Result> stopAll() {
        try {
            return ResponseEntity.ok(Result.ok(panServiceManager.stopAll()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Result.error(500, "停止失败: " + e.getMessage()));
        }
    }

    /** 支持的服务列表 */
    @GetMapping("/services")
    public ResponseEntity<Result> services() {
        return ResponseEntity.ok(Result.ok(List.of(
                Map.of("service", "pan123", "name", "123云盘", "port", 5001, "defaultOff", true),
                Map.of("service", "aliyun", "name", "阿里云盘", "port", 5000, "defaultOff", true)
        )));
    }
}
