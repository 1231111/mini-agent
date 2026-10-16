package com.miniagent.agent.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能目录的路径边界。
 *
 * <p>背景：{@code skill_manage} 的 {@code name} / {@code category} 都来自模型。
 * 旧实现里 {@code name} 有 {@code sanitizeName} 兜着，但 {@code category} 是<b>原样</b>
 * 拼进 {@code resolve()} 的；而 {@code findSkillMd(name)} 也用<b>裸 name</b> 直接拼路径 ——
 * 于是读、写、删三条路都能走出 skills 目录（删那条会递归删任意含 SKILL.md 的目录）。</p>
 *
 * <p>这些用例把"越界一律拒绝、且目录外文件毫发无损"钉死，同时确认正当能力（嵌套分类、
 * 读技能包内的 references 子文件）没有被误伤。</p>
 */
class SkillStoreTraversalTest {

    private Path base;
    private Path skillsDir;
    private SkillStore store;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory(
                Files.createDirectories(Path.of(System.getProperty("user.dir")).resolve("target")),
                "skills-boundary");
        skillsDir = base.resolve("skills");
        store = new SkillStore(skillsDir);

        // 目录外放一个"受害者"技能包，用来验证越界操作碰不到它
        Path victim = base.resolve("victim").resolve("real-skill");
        Files.createDirectories(victim);
        Files.writeString(victim.resolve("SKILL.md"),
                "---\nname: real-skill\ndescription: victim\n---\n\n敏感内容\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void categoryWithTraversalCannotWriteOutsideSkillsDir() {
        for (String category : new String[]{"../escape", "..\\..\\escape", "C:\\Windows\\Temp", "/etc"}) {
            Map<String, Object> result = store.createSkill("evil", "desc", "payload", category);
            assertEquals(Boolean.FALSE, result.get("success"),
                    "分类含越界内容必须被拒: " + category);
            assertTrue(String.valueOf(result.get("error")).contains("路径段"),
                    "拒绝消息要说清是路径段不合法: " + result.get("error"));
        }
        assertFalse(Files.exists(base.resolve("escape")), "不得在 skills 根之外创建目录");
    }

    @Test
    void traversalNameCannotDeleteOutsideSkill() {
        Map<String, Object> result = store.deleteSkill("../victim/real-skill");

        assertEquals(Boolean.FALSE, result.get("success"), "越界名字必须当作'找不到'");
        assertTrue(Files.exists(base.resolve("victim").resolve("real-skill").resolve("SKILL.md")),
                "skills 目录之外的技能必须毫发无损（这条是递归删除漏洞的回归锁）");
    }

    @Test
    void traversalNameCannotReadOutsideSkill() {
        assertNull(store.viewSkill("../victim/real-skill"),
                "越界名字读不到外部 SKILL.md");
        assertNull(store.viewSkill("..\\..\\victim\\real-skill"));

        // 正常的名字读到的必须是自己那份内容，而不是目录外受害者那份。
        // 注意这里要先真的建出 good —— 不存在的技能 viewSkill 本来返回 null，
        // 拿 null 去断言"不含敏感内容"会把"没找到"误当成"防住了"。
        assertEquals(Boolean.TRUE, store.createSkill("good", "desc", "self-body", null).get("success"));
        String content = store.viewSkill("good");
        assertTrue(content != null && content.contains("self-body"),
                "正常名字应读到自己的内容: " + content);
        assertFalse(content.contains("敏感内容"), "正常名字不应意外读到受害者内容");
    }

    @Test
    void nestedRelativeReadWorksButEscapeIsRejected() throws IOException {
        assertEquals(Boolean.TRUE, store.createSkill("good", "desc", "body", null).get("success"));
        Path skillDir = skillsDir.resolve("good");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("references").resolve("spec.md"), "spec-body",
                StandardCharsets.UTF_8);

        assertEquals("spec-body", store.viewSkillFile("good", "references/spec.md"),
                "技能包内多级子文件必须仍可读（这是正当能力，不能被一刀切掉）");
        assertEquals("Error: path traversal",
                store.viewSkillFile("good", "../../victim/real-skill/SKILL.md"),
                "带 .. 的相对路径必须被拒");
    }

    @Test
    void legalCategoryStillWorks() {
        assertEquals(Boolean.TRUE, store.createSkill("mysql-tuner", "desc", "body", "database").get("success"));
        assertTrue(Files.exists(skillsDir.resolve("database").resolve("mysql-tuner").resolve("SKILL.md")),
                "合法的分类层级必须照常创建");
    }

    @Test
    void nameStillSanitizedOnCreation() {
        assertEquals(Boolean.TRUE, store.createSkill("My Skill!", "desc", "body", null).get("success"));
        assertTrue(Files.exists(skillsDir.resolve("my-skill").resolve("SKILL.md")),
                "名字仍按既有规则净化（大写/特殊字符转连字符）");
    }
}
