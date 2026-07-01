package com.miniagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 静态资源配置 — 让 generated-images/ 目录可通过 HTTP 访问。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String GENERATED_IMAGES_DIR =
            Path.of(System.getProperty("user.dir")).toAbsolutePath()
                    .resolve("generated-images").toUri().toString();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/generated-images/**")
                .addResourceLocations(GENERATED_IMAGES_DIR);
    }
}
