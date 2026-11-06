package com.miniagent.agent.coding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link CodingDiagnosticsProperties}。
 *
 * <p>项目里没有全局 {@code @ConfigurationPropertiesScan}，配置类必须显式登记，
 * 否则 {@code agent.coding.diagnostics.*} 里的值全部读不到 —— 症状是「配了没生效」，
 * 而 Spring 不会报错。</p>
 */
@Configuration
@EnableConfigurationProperties(CodingDiagnosticsProperties.class)
public class CodingDiagnosticsConfig {
}
