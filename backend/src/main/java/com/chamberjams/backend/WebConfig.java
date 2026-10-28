package com.chamberjams.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(
                "https://kwon-daegun.github.io",
                "http://localhost:5173",
                "http://localhost:5174"
            )
            .allowedMethods(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "OPTIONS"
            )
            .allowedHeaders(
                "Content-Type",
                "X-Telegram-Init-Data",
                "Range",
                "Accept",
                "Origin"
            )
            .exposedHeaders(
                "Content-Range",
                "Accept-Ranges",
                "Content-Length"
            );
    }
}