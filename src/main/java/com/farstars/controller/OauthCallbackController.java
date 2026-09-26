package com.farstars.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 百度 OAuth 回调页: 授权完成后百度携带 access_token 重定向到 /oauth/baidu#access_token=xxx,
 * 此页面提取 token 存入 localStorage(与前端同源) 并自动跳回百度网盘页面, 免除手动复制回填.
 */
@RestController
public class OauthCallbackController {

    @GetMapping(value = "/oauth/baidu", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> baiduCallback() {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>授权完成</title></head><body>"
                + "<p style=\"font-family:sans-serif;padding:40px;text-align:center;\">授权完成，正在自动回填 access_token…</p>"
                + "<script>"
                + "try{"
                + "  var h=location.hash.substring(1);"
                + "  var p=new URLSearchParams(h);"
                + "  var t=p.get('access_token');"
                + "  if(t){localStorage.setItem('panflow.baidu.token', t);}"
                + "}catch(e){}"
                + "setTimeout(function(){location.href='/';}, 800);"
                + "</script>"
                + "</body></html>";
        return ResponseEntity.ok(html);
    }
}
