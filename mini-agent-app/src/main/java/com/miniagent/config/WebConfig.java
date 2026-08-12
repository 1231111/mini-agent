package com.miniagent.config;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.memory.AgentDataPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 静态资源：生成图 + 会话附图。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AgentDataPaths dataPaths;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/generated-images/**")
                .addResourceLocations(dirLocation(dataPaths.mediaGenerated()));
        // 与落盘键 conversation-images/{session}/{file} 对齐
        registry.addResourceHandler("/conversation-images/**")
                .addResourceLocations(dirLocation(dataPaths.mediaConversations()));
    }

    /** file: 目录必须带尾斜杠，否则 createRelative 会吃掉最后一级目录名导致 404 */
    static String dirLocation(Path dir) {
        String uri = dir.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
