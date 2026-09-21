package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryTest {

    private static final Set<String> REGISTRY = Set.of(
            "web_search", "write_file", "browser_navigate", "todo",
            "skill_list", "memory", "read_file", "exec_command");

    /**
     * 按注册表构造真实 {@link CapabilityRegistry}，再取能力包。
     *
     * <p>与生产同一入口（{@code toolsFor}）：之前这里走的是一个只给测试用的静态方法，
     * 那条路径已随意图面一起删除，改测真实路径 —— 否则测过的代码上线不跑。</p>
     */
    private static Set<String> pack(Set<String> registered, String... capabilities) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : registered) {
            registry.register(name, name, Map.of("type", "object"), x -> "ok");
        }
        CapabilityRegistry caps = new CapabilityRegistry(registry);
        Set<String> out = new LinkedHashSet<>();
        for (String cap : capabilities) {
            out.addAll(caps.toolsFor(cap));
        }
        return out;
    }

    @Test
    void webPackExcludesWrite() {
        Set<String> hit = pack(REGISTRY, "web", "todo", "memory");
        assertTrue(hit.contains("web_search"));
        assertTrue(hit.contains("browser_navigate"));
        assertTrue(hit.contains("todo"));
        assertFalse(hit.contains("write_file"));
        assertFalse(hit.contains("skill_list"));
    }

    @Test
    void plannerResearchPackExcludesWrite() {
        Set<String> hit = pack(REGISTRY, "research");
        assertTrue(hit.contains("web_search"));
        assertFalse(hit.contains("write_file"));
    }

    @Test
    void plannerCapabilitiesHavePacks() {
        for (String cap : CapabilityRegistry.PLANNER_CAPABILITIES) {
            Set<String> hit = pack(REGISTRY, cap);
            assertFalse(hit.isEmpty(), "empty pack: " + cap);
        }
        assertTrue(CapabilityRegistry.knownCapability("web"));
        assertFalse(CapabilityRegistry.knownCapability("teleport"));
        assertTrue(CapabilityRegistry.writesFiles("file_write"));
        assertTrue(CapabilityRegistry.writesFiles("image"));
        assertFalse(CapabilityRegistry.writesFiles("browser"));
        assertFalse(CapabilityRegistry.writesFiles("web"));
        assertTrue(CapabilityRegistry.persistsArtifacts("file_write"));
        assertFalse(CapabilityRegistry.persistsArtifacts("code"));
        assertFalse(CapabilityRegistry.persistsArtifacts("web"));
        assertTrue(CapabilityRegistry.acquires("web"));
        assertTrue(CapabilityRegistry.acquires("file_read"));
        assertFalse(CapabilityRegistry.acquires("file_write"));
        assertTrue(pack(REGISTRY, "file_write").contains("exec_command"));
    }

    /**
     * {@code qa} 不在 PLANNER_CAPABILITIES 里（那张表是给模型的提示词词表），
     * 但它是 {@code GoalCompiler.inferFromPlan} 对纯问答轮的出口，必须一起自检 ——
     * 否则这张包坏了启动期不会报，只在线上问答轮静默退化。
     */
    @Test
    void selfCheckCoversCapabilitiesEmittedByCode() {
        assertTrue(CapabilityRegistry.checkedCapabilities().contains("qa"));
        assertTrue(CapabilityRegistry.checkedCapabilities()
                .containsAll(CapabilityRegistry.PLANNER_CAPABILITIES));
        assertTrue(CapabilityRegistry.knownCapability("qa"));
        Set<String> hit = pack(REGISTRY, "qa");
        assertTrue(hit.contains("skill_list"));
        assertFalse(hit.contains("write_file"));
    }

    @Test
    void imagePackIncludesTtsAndStatus() {
        Set<String> registered = Set.of(
                "image_generate", "comfyui_status", "comfyui_tts",
                "comfyui_img2video", "write_file", "web_search");
        Set<String> hit = pack(registered, "image");
        assertTrue(hit.contains("comfyui_tts"));
        assertTrue(hit.contains("comfyui_status"));
        assertTrue(hit.contains("comfyui_img2video"));
        assertTrue(hit.contains("image_generate"));
        assertFalse(hit.contains("web_search"));
    }

    @Test
    void descriptionKeywordsDoNotJoinPack() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("weird_tool", "写入文件的神秘工具",
                Map.of("type", "object"), x -> "ok");
        registry.register("write_file", "write",
                Map.of("type", "object"), x -> "ok");
        CapabilityRegistry caps = new CapabilityRegistry(registry);
        assertFalse(caps.toolsFor("file").contains("weird_tool"));
        assertTrue(caps.toolsFor("file_write").contains("write_file"));
    }

    @Test
    void unknownCapabilityFallsBackToGeneralNotToDeadStep() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : REGISTRY) {
            registry.register(name, name, Map.of("type", "object"), x -> "ok");
        }
        CapabilityRegistry caps = new CapabilityRegistry(registry);
        List<String> hit = caps.toolsFor("teleport");
        assertTrue(hit.contains("read_file"));
        assertTrue(hit.contains("write_file"));
        assertTrue(hit.contains("web_search"));
    }
}
