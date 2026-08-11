package com.miniagent.config;

import com.miniagent.memory.AgentDataPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源：/generated-images/** → data-dir/media/generated
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AgentDataPaths dataPaths;

    public WebConfig(AgentDataPaths dataPaths) {
        this.dataPaths = dataPaths;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = dataPaths.mediaGenerated().toUri().toString();
        registry.addResourceHandler("/generated-images/**")
                .addResourceLocations(location);
    }
}
