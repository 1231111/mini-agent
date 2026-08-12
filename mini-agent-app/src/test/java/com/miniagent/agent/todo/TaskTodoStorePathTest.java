package com.miniagent.agent.todo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskTodoStorePathTest {

    @Test
    void extractPathSpec_stripsPrefixAndNote() {
        assertEquals(
                "C:/Users/abc/.miniagent/workspace/default/a.md",
                TaskTodoStore.extractPathSpec(
                        "file_exists:C:/Users/abc/.miniagent/workspace/default/a.md（28814字节，完整）"));
        assertEquals(
                "workspace/default/a.md",
                TaskTodoStore.extractPathSpec("file_exists:workspace/default/a.md"));
    }

    @Test
    void verifyCompletion_acceptsFileExistsEvidenceWithPrefix() throws Exception {
        Path dir = Files.createTempDirectory("todo-path-");
        Path file = dir.resolve("doc.md");
        Files.writeString(file, "# ok", StandardCharsets.UTF_8);
        String abs = file.toAbsolutePath().toString().replace('\\', '/');

        TaskTodoStore store = new TaskTodoStore(dir.resolve("todos").toString());
        assertNull(store.verifyCompletion(
                "file_exists:" + abs,
                "file_exists:" + abs + "（说明文字）"));
        assertNull(store.verifyCompletion(
                "file_exists:missing.md",
                "file_exists:" + abs));
    }
}
