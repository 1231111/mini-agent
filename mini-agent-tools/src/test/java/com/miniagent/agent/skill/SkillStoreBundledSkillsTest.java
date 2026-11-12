package com.miniagent.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStoreBundledSkillsTest {

    @TempDir
    Path tempDir;

    @Test
    void freshStoreInstallsBundledSkills() {
        SkillStore store = new SkillStore(tempDir.resolve("skills"));

        assertEquals(7, store.listSkills().size());
        assertTrue(store.viewSkill("desktop-workflow").contains("desktop_observe"));
        assertTrue(store.viewSkill("web-research").contains("Use `web_search`"));
        assertTrue(store.viewSkill("ai-news-briefing").contains("coverage window"));
        assertTrue(store.viewSkill("document-authoring").contains("write_document"));
        assertTrue(store.viewSkill("codebase-analysis").contains("search_code"));
        assertTrue(store.viewSkill("browser-workflow").contains("browser_snapshot"));
        assertTrue(store.viewSkill("travel-search").contains("miniagent-flight"));
        assertTrue(store.viewSkill("travel-search").contains("12306"));
    }

    @Test
    void seedingNeverOverwritesOrRestoresUserChanges() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path customSkill = skillsDir.resolve("web-research").resolve("SKILL.md");
        Files.createDirectories(customSkill.getParent());
        Files.writeString(customSkill, "user-edited skill", StandardCharsets.UTF_8);

        SkillStore firstStart = new SkillStore(skillsDir);
        assertEquals("user-edited skill", firstStart.viewSkill("web-research"));
        assertEquals(Boolean.TRUE, firstStart.deleteSkill("ai-news-briefing").get("success"));

        SkillStore nextStart = new SkillStore(skillsDir);
        assertEquals("user-edited skill", nextStart.viewSkill("web-research"));
        assertFalse(Files.exists(skillsDir.resolve("ai-news-briefing").resolve("SKILL.md")));
    }
}
