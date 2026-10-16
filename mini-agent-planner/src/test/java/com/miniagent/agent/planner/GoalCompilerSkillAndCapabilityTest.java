package com.miniagent.agent.planner;

import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.tool.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器的三件事：词表来自注册表、提示词说清 media 语义、看得见 skill。
 *
 * <p>这三条都是 2026-10-15「帮我写一首歌」的实测结论固化下来的：
 * 系统提示把 {@code image} 只解释成「架构图/流程图」，模型给写歌任务挑了
 * {@code file_write}，提案闸门再把 {@code song_generate} 裁掉 ——
 * 模型从头到尾没见过生歌工具，只写了个歌词 md 就报「本步已完成」。</p>
 */
class GoalCompilerSkillAndCapabilityTest {

    private static GoalCompiler compiler() {
        return new GoalCompiler(new PlannerProperties());
    }

    @Test
    void 词表与注册表唯一事实源一致且不含general() {
        String vocab = GoalCompiler.capabilityVocabulary();
        assertFalse(vocab.contains(CapabilityRegistry.GENERAL),
                "general 是兜底出口，不能出现在给模型的词表里：" + vocab);
        for (String cap : CapabilityRegistry.PLANNER_CAPABILITIES) {
            if (!CapabilityRegistry.GENERAL.equals(cap)) {
                assertTrue(vocab.contains(cap), "词表漏了注册表里的能力：" + cap);
            }
        }
    }

    @Test
    void 系统提示已注入词表且无残留占位符() {
        String sys = GoalCompiler.compilerSystem();
        assertFalse(sys.contains("{CAPS}"), "占位符没被替换，模型会看到花括号：");
        assertTrue(sys.contains("image"), "image 必须出现在词表里");
    }

    @Test
    void 系统提示把image讲成媒体生成而非只讲架构图() {
        String sys = GoalCompiler.compilerSystem();
        assertTrue(sys.contains("创作歌曲"), "没告诉模型「创作歌曲」属于 image 能力：");
        assertTrue(sys.contains("media_delivered"),
                "媒体生成节点必须知道用 media_delivered 验收：");
    }

    @Test
    void 未装配skillStore时摘要为空串() {
        assertEquals("", compiler().skillDigest(),
                "planner 单测不装配 skill 时应退化成「不看技能」，而不是抛异常");
    }

    @Test
    void 装配skill后摘要带出技能名与触发词(@TempDir Path dir) throws Exception {
        Path skillDir = dir.resolve("music-generation");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: music-generation
                description: "生成歌曲（作词 + 作曲 + 演唱）。触发词：写歌、生成一首歌。"
                ---
                # 生歌
                """);

        GoalCompiler c = compiler();
        c.attachSkillStore(new SkillStore(dir));
        String digest = c.skillDigest();
        assertTrue(digest.contains("music-generation"), "摘要里应能读到技能名：" + digest);
        assertTrue(digest.contains("capability"), "摘要要指明它约束的是 capability 选择：" + digest);
    }

    @Test
    void 空技能目录返回空摘要(@TempDir Path dir) {
        GoalCompiler c = compiler();
        c.attachSkillStore(new SkillStore(dir));
        assertEquals("", c.skillDigest(), "没有 skill 时不应注入空壳段落");
    }
}