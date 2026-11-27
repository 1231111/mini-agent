package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryFileReadContractTest {
    @Test
    void fileAnalysisCanLocateSymbolsWithoutReceivingWriteShellOrBrowserTools() {
        ToolRegistry tools = registry("read_file", "list_files", "read_package", "image_inspect",
                "search_code", "ast_search", "codebase_search", "write_file", "edit_file",
                "exec_command", "browser_navigate", "http_post", "todo");
        CapabilityRegistry capabilities = new CapabilityRegistry(tools);

        assertThat(capabilities.toolsFor("file_read"))
                .containsExactly("read_file", "list_files", "read_package", "image_inspect", "search_code")
                .doesNotContain("write_file", "edit_file", "exec_command", "browser_navigate", "http_post");
        assertThat(ToolConcurrencyPolicy.sideEffectOf("search_code")).isEqualTo(ToolSideEffect.READ_ONLY);
        assertThat(CapabilityRegistry.writesFiles("file_read")).isFalse();
        assertThat(CapabilityRegistry.persistsArtifacts("file_read")).isFalse();
    }

    @Test
    void anUnavailableSearchImplementationIsNotAdvertisedAndHotRegistrationRefreshesTheFamily() {
        ToolRegistry tools = registry("read_file", "write_file", "exec_command");
        CapabilityRegistry capabilities = new CapabilityRegistry(tools);
        assertThat(capabilities.toolsFor("file_read")).containsExactly("read_file");

        tools.register("search_code", "bounded read-only search", Map.of(), ignored -> "matched");
        assertThat(capabilities.toolsFor("file_read")).containsExactly("read_file", "search_code");

        tools.unregister("search_code");
        assertThat(capabilities.toolsFor("file_read")).containsExactly("read_file");
    }

    private static ToolRegistry registry(String... names) {
        ToolRegistry tools = new ToolRegistry();
        List.of(names).forEach(name -> tools.register(name, "contract fixture", Map.of(), ignored -> "ok"));
        return tools;
    }
}
