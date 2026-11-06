package com.miniagent.agent.memory.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 配置云端地址后装配；Account 签发的用户令牌运行时从影子用户读取。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnCloudMemory.OnCloudMemory.class)
public @interface ConditionalOnCloudMemory {

    class OnCloudMemory implements Condition {
        static final String URL = "agent.memory.service.base-url";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return present(context, URL);
        }

        private static boolean present(ConditionContext context, String name) {
            String value = context.getEnvironment().getProperty(name);
            return value != null && !value.isBlank();
        }
    }
}
