package com.miniagent.web;

import com.miniagent.application.ConversationWorkspaceService;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.storage.MediaStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceFileReferenceTest {
    @TempDir
    Path root;

    @Test
    void nestedAndAbsoluteReferencesDownloadFromOwnedBoundWorkspace() throws Exception {
        Path file = root.resolve("frontend/vite.config.js");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "export default {};");
        MiniAgentChatPageController controller = controller();

        for (String reference : new String[]{"frontend/vite.config.js", file.toString()}) {
            var response = controller.downloadWorkspaceReference(reference, "owned", authenticated());
            assertEquals(200, response.getStatusCode().value());
            assertEquals("vite.config.js", response.getHeaders().getContentDisposition().getFilename());
            assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
            assertEquals("export default {};",
                    ((Resource) response.getBody()).getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void wildcardAndGeneratedHtmlFragmentsAreClientErrorsRatherThanServerErrors() {
        var controller = controller();
        assertEquals(400, controller.downloadWorkspaceFile("start_*.bat", "owned", authenticated())
                .getStatusCode().value());
        assertEquals(400, controller.downloadWorkspaceFile("start_<em>.bat", "owned", authenticated())
                .getStatusCode().value());
        assertEquals(400, controller.downloadWorkspaceReference("start_*.bat", "owned", authenticated())
                .getStatusCode().value());
    }

    @Test
    void newReferenceEndpointRequiresAuthenticationAndSessionOwnership() {
        var controller = controller();
        assertEquals(401, controller.downloadWorkspaceReference("README.md", "owned", new MockHttpServletRequest())
                .getStatusCode().value());
        assertEquals(400, controller.downloadWorkspaceReference("README.md", null, authenticated())
                .getStatusCode().value());
        assertEquals(404, controller.downloadWorkspaceReference("README.md", "another-users-session", authenticated())
                .getStatusCode().value());
    }

    @Test
    void headMappingsCheckExistenceAndOwnershipWithoutReturningFileBodies() throws Exception {
        Files.writeString(root.resolve("README.md"), "# readme");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/app.js"), "export {}");
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        var principal = new AuthenticatedUser(7L, 1L, "user", UserRole.USER);

        mvc.perform(head("/api/workspace-download/README.md").param("sid", "owned")
                        .requestAttr(JwtSessionService.ATTR_PRINCIPAL, principal))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().longValue("Content-Length", 8));
        mvc.perform(head("/api/workspace-download").param("path", "src/app.js").param("sid", "owned")
                        .requestAttr(JwtSessionService.ATTR_PRINCIPAL, principal))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().longValue("Content-Length", 9));
        mvc.perform(head("/api/workspace-download").param("path", "src/app.js")
                        .param("sid", "someone-else")
                        .requestAttr(JwtSessionService.ATTR_PRINCIPAL, principal))
                .andExpect(status().isNotFound()).andExpect(content().bytes(new byte[0]));
        mvc.perform(head("/api/workspace-download").param("path", "src/app.js").param("sid", "owned"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void externalAndHiddenFilesCannotBeReachedByChangingTheReference() throws Exception {
        Path outside = Files.createTempFile(root.getParent(), "outside-reference-", ".txt");
        Files.writeString(root.resolve(".env"), "secret");
        assertNull(MiniAgentChatPageController.resolveWorkspaceReference(root, outside.toString()));
        assertNull(MiniAgentChatPageController.resolveWorkspaceReference(root, "../" + outside.getFileName()));
        assertNull(MiniAgentChatPageController.resolveWorkspaceReference(root, ".env"));
        assertNull(MiniAgentChatPageController.resolveWorkspaceReference(root, "missing.md"));
    }

    @Test
    void nestedSymlinkCannotEscapeTheSessionWorkspace() throws Exception {
        Path outside = Files.createTempFile(root.getParent(), "outside-symlink-", ".md");
        Path directory = Files.createDirectory(root.resolve("docs"));
        Path link = directory.resolve("README.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
        assertNull(MiniAgentChatPageController.resolveWorkspaceReference(root, "docs/README.md"));
    }

    private MiniAgentChatPageController controller() {
        var controller = new MiniAgentChatPageController();
        MediaStorage storage = mock(MediaStorage.class);
        when(storage.workspaceDir()).thenReturn(root);
        SessionAuthorizationService authorization = mock(SessionAuthorizationService.class);
        when(authorization.owns(7L, "owned")).thenReturn(true);
        ConversationWorkspaceService workspace = mock(ConversationWorkspaceService.class);
        when(workspace.sessionRoot("owned", 7L)).thenReturn(Optional.of(root));
        ReflectionTestUtils.setField(controller, "mediaStorage", storage);
        ReflectionTestUtils.setField(controller, "sessionAuthorization", authorization);
        ReflectionTestUtils.setField(controller, "conversationWorkspaceService", workspace);
        return controller;
    }

    private static MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL, new AuthenticatedUser(7L, 1L, "user", UserRole.USER));
        return request;
    }
}
