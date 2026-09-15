package com.miniagent.agent.tool;

import java.lang.annotation.*;

/**
 * 工具参数定义注解：标记字段为工具参数
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParamSchema {
    /**
     * 参数描述
     */
    String description();

    /**
     * 是否必填（默认false）
     */
    boolean required() default false;

    /**
     * 默认值（字符串形式，空表示无默认值）
     */
    String defaultValue() default "";
}