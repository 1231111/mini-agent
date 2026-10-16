package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖注入契约：非 static、非 final 的依赖字段必须带 {@code @Autowired} 或 {@code @Value}。
 *
 * <p><b>为什么要有这条</b>：漏注解不会让 Spring 启动失败，Bean 照样建好，
 * 工具也照样注册进注册表 —— 症状要等到模型真的去调那个工具时才出现，
 * 而且长成「模型说工具调用出错、但工具清单里明明有它」这种极难定位的样子。
 * 2026-10-15 实测：{@code BuiltinTools.skillStore} 漏注解，
 * {@code skill_list} / {@code skill_view} / {@code skill_manage} 三个工具全是空壳，
 * 至今只有让模型自己说漏嘴才被发现。反射扫一遍比等模型报错便宜几个数量级。</p>
 */
class BuiltinToolsInjectionTest {

    @Test
    void 依赖字段都带注入注解() {
        List<String> missing = new ArrayList<>();
        for (Field f : BuiltinTools.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.isSynthetic() || Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                continue;
            }
            if (f.getAnnotation(Autowired.class) != null || f.getAnnotation(Value.class) != null) {
                continue;
            }
            missing.add(f.getName() + ":" + f.getType().getSimpleName());
        }
        assertTrue(missing.isEmpty(),
                "这些字段没有任何注入注解，运行时会是 null（工具注册照常成功、调用才炸）：" + missing);
    }
}