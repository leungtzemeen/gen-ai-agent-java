package com.gen.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gen.ai.web.LocalhostOnlyAdminInterceptor;

/**
 * 全局 CORS（减轻前后端分离时的浏览器预检问题）；运维接口 {@code /ai/admin/**} 仅本机放行。
 */
@Configuration
public class WebMvcAppConfiguration implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new LocalhostOnlyAdminInterceptor()).addPathPatterns("/ai/admin/**");
    }
}
