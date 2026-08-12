package com.miniagent.config;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.memory.AgentDataPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                .addResourceLocations(dataPaths.mediaGenerated().toUri().toString());
        // 与落盘键 conversation-images/{session}/{file} 对齐
        registry.addResourceHandler("/conversation-images/**")
                .addResourceLocations(dataPaths.mediaConversations().toUri().toString());
    }
}
