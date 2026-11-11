package com.miniagent.agent.tool;

import com.miniagent.agent.skill.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolsSkillViewTest {

    @TempDir
    Path tempDir;

    @Test
    void skillTextContainingDependencyKeywordsIsReturnedAsSuccess() throws Exception {
        SkillStore store = new SkillStore(tempDir.resolve("skills"));
        String body = "---\nname: travel-search\n---\nMark unavailable fields as unknown.";
        Path skillFile = tempDir.resolve("skills/travel-search/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, body, StandardCharsets.UTF_8);

        ToolResult result = executeSkillView(store, "{\"name\":\"travel-search\"}");

        assertTrue(result.isSuccess(), "skill document content must not be classified by legacy error keywords");
        assertEquals(ToolErrorCode.NONE, result.errorCode());
        assertEquals(body, result.legacyText());
    }

    @Test
    void missingSkillReturnsTypedNotFoundFailure() throws Exception {
        ToolResult result = executeSkillView(new SkillStore(tempDir.resolve("missing-skills")),
                "{\"name\":\"does-not-exist\"}");

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode());
        assertTrue(result.legacyText().contains("Skill not found: does-not-exist"));
    }

    @Test
    void skillSubfileTextIsNotTreatedAsLegacyPathError() throws Exception {
        Path skillsDir = tempDir.resolve("subfile-skills");
        SkillStore store = new SkillStore(skillsDir);
        Path skillFile = skillsDir.resolve("travel-search/references/notes.md");
        Files.createDirectories(skillFile.getParent());
        String body = "Error: path traversal is an example in this documentation.\n"
                + "Mark unavailable fields as unknown.";
        Files.writeString(skillFile, body, StandardCharsets.UTF_8);

        ToolResult result = executeSkillView(store,
                "{\"name\":\"travel-search\",\"file_path\":\"references/notes.md\"}");

        assertTrue(result.isSuccess());
        assertEquals(body, result.legacyText());
    }

    @Test
    void skillSubfileTraversalIsTypedPermissionFailure() throws Exception {
        SkillStore store = new SkillStore(tempDir.resolve("traversal-skills"));

        ToolResult result = executeSkillView(store,
                "{\"name\":\"travel-search\",\"file_path\":\"../outside.md\"}");

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.PERMISSION_DENIED, result.errorCode());
    }

    private ToolResult executeSkillView(SkillStore store, String arguments) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools builtins = new BuiltinTools();
        setField(builtins, "registry", registry);
        setField(builtins, "skillStore", store);

        Method registerSkillTools = BuiltinTools.class.getDeclaredMethod("registerSkillTools");
        registerSkillTools.setAccessible(true);
        registerSkillTools.invoke(builtins);
        return registry.executeResult("skill_view", arguments);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = BuiltinTools.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
