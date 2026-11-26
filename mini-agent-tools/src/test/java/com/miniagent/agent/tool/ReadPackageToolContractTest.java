package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReadPackageToolContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_DIR_PROPERTY = "user.dir";

    @TempDir
    Path project;

    private String originalUserDir;

    @BeforeEach
    void setProjectRoot() {
        originalUserDir = System.getProperty(USER_DIR_PROPERTY);
        System.setProperty(USER_DIR_PROPERTY, project.toString());
        WorkspaceContext.setRootOverride(project.resolve("workspace"));
        PathGuard.clearExtraRoots();
    }

    @AfterEach
    void restoreGlobalState() {
        if (originalUserDir == null) System.clearProperty(USER_DIR_PROPERTY);
        else System.setProperty(USER_DIR_PROPERTY, originalUserDir);
        WorkspaceContext.clearAll();
        PathGuard.clearExtraRoots();
    }

    @Test
    void rejectsAbsolutePathsTraversalAndMalformedPackageSegmentsBeforeFilesystemLookup() {
        ToolRegistry registry = newRegistry();
        for (String invalid : new String[]{"C:\\Users\\abc\\.ssh", "/etc/ssh", "../secret",
                "com..example", "com.example/../../secret", "com.class.service", "com.example;rm"}) {
            ToolResult result = registry.executeResult("read_package",
                    JSON.createObjectNode().put("packageName", invalid).toString());
            assertThat(result.isSuccess()).as("package=%s", invalid).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
            assertThat(result.message()).contains("合法的点分隔 Java 包名");
        }
    }

    @Test
    void readsOnlyRegularSourceRootsAtProjectRootOrOneModuleLevel() throws Exception {
        Path packageDir = project.resolve("mini-agent-tools/src/main/java/com/example/tool");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("Tool.java"), "package com.example.tool;\nclass Tool {}\n");
        Path nestedRoot = project.resolve("nested/module/deep/src/main/java/com/example/tool");
        Files.createDirectories(nestedRoot);
        Files.writeString(nestedRoot.resolve("Hidden.java"), "package com.example.tool;\nclass Hidden {}\n");
        PathGuard.allowRoots(project.toString());

        ToolResult result = newRegistry().executeResult("read_package", "{\"packageName\":\"com.example.tool\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText()).contains("root=module:mini-agent-tools", "Tool.java", "files_returned=1")
                .doesNotContain("Hidden.java");
    }

    @Test
    void legalPackageReadHasHardPerFileAndTotalResponseLimitsAndPartialMetadata() throws Exception {
        Path packageDir = project.resolve("src/main/java/com/example/big");
        Files.createDirectories(packageDir);
        IntStream.range(0, 40).forEach(i -> {
            try {
                Files.writeString(packageDir.resolve(String.format("File%02d.java", i)),
                        ("class File" + i + " { String value = \"" + "x".repeat(5000) + "\"; }\n"));
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });

        ToolResult result = newRegistry().executeResult("read_package",
                "{\"packageName\":\"com.example.big\",\"maxCharsPerFile\":1000000}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.legacyText().length()).isLessThanOrEqualTo(12_000);
        assertThat(result.legacyText()).contains("root=project", "partial=true", "truncated_files=");
        assertThat(result.legacyText()).doesNotContain("File39.java");
    }

    @Test
    void invalidUtf8OrSensitivePackageFileFailsClosedWithoutReturningItsContents() throws Exception {
        Path packageDir = project.resolve("src/main/java/com/example/malformed");
        Files.createDirectories(packageDir);
        Files.write(packageDir.resolve("Source.java"), new byte[]{(byte) 0xc3, 0x28});

        ToolResult result = newRegistry().executeResult("read_package",
                "{\"packageName\":\"com.example.malformed\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(result.legacyText()).doesNotContain("Source.java");
    }

    private ToolRegistry newRegistry() {
        ToolRegistry registry = new ToolRegistry();
        BuiltinTools tools = new BuiltinTools();
        ReflectionTestUtils.setField(tools, "registry", registry);
        ReflectionTestUtils.invokeMethod(tools, "registerFileTools");
        return registry;
    }
}
