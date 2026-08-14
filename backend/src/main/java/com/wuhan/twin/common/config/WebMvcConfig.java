package com.wuhan.twin.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置，当前仅放行跨域
 *
 * @author lvfan
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 预检请求缓存秒数
     */
    private static final long MAX_AGE = 3600L;

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
                // 用 allowedOriginPatterns 而非 allowedOrigins("*")，后者与 allowCredentials(true) 冲突会启动失败
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(MAX_AGE);
    }
}
