package com.farstars.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源缓存策略:
 * - index.html 不缓存(no-cache): 前端每次刷新都能获取最新 index.html,
 *   从而加载带新 hash 的 JS(避免浏览器缓存旧版前端导致功能不更新)
 * - 带 hash 的 JS/CSS 等资源仍走默认缓存(Spring Boot 默认)
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
