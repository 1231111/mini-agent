package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.context.ContextContributorConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRouterCapabilityContractTest {

    @Test
    void researchNodeReceivesCodeSearchToolsWithoutWriteOrShellCapabilities() {
        ToolRegistry tools = new ToolRegistry();
        List.of("read_file", "list_files", "read_package", "image_inspect",
                "web_search", "browser_navigate",
                "search_code", "codebase_search", "ast_search",
                "write_file", "exec_command", "todo")
                .forEach(name -> tools.register(name, "test tool", Map.of(), ignored -> "ok"));

        CapabilityRegistry capabilities = new CapabilityRegistry(tools);
        ToolRouter router = new ToolRouter(capabilities);
        TaskNode node = new TaskNode("research-node", "梳理现有游戏逻辑", "research",
                List.of(), List.of(), List.of(), TaskNodeStatus.READY, 50,
                DoneWhen.note(), "", "", 0, "");
        ActionProposal proposal = new ActionProposal("proposal", 1, "execution",
                List.of(new ActionSpec("action", "research-node", "read_file",
                        Map.of("path", "index.html"), "note_required", "")));

        List<String> allowed = router.allowedFor(proposal, new TaskGraph(List.of(node)), true);

        assertThat(allowed).contains("search_code", "codebase_search", "ast_search",
                "read_file", "web_search", "browser_navigate", "todo");
        assertThat(allowed).doesNotContain("write_file", "exec_command");
        assertThat(ContextContributorConfiguration.toolGuidance(
                java.util.Set.copyOf(allowed), true)).contains("代码检索", "search_code");
    }
}
