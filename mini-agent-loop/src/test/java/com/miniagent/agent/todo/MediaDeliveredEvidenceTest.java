package com.miniagent.agent.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDeliveredEvidenceTest {

    @TempDir
    Path dir;

    @Test
    void localPngCountsAsDelivered() throws Exception {
        Path png = dir.resolve("agent_architecture.png");
        Files.write(png, new byte[200]);
        String path = png.toString();
        String json = "{\"success\":true,\"path\":\"" + path.replace("\\", "/") + "\",\"size\":200}";

        TodoSemanticValidator.Result byPath = TodoSemanticValidator.validate(
                "架构图", "media_delivered", path);
        TodoSemanticValidator.Result byJson = TodoSemanticValidator.validate(
                "架构图", "media_delivered", json);
        assertTrue(byPath.ok());
        assertTrue(byJson.ok());
        assertNull(new TaskTodoStore(dir.toString()).verifyCompletion("media_delivered", path));
    }
}
