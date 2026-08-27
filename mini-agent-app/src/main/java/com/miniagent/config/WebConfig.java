package com.miniagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 扩展点。用户媒体不得注册为静态资源，必须经过认证 Controller。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

}
