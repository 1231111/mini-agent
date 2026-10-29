package com.miniagent.application;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import com.miniagent.agent.memory.entity.AgentProjectMemberEntity;
import com.miniagent.agent.memory.repository.AgentProjectMemberRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.agent.memory.service.AgentProjectAuthorizationService;
import com.miniagent.agent.memory.manager.ProjectAuthorizationSyncService;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.entity.ChatConversation;
import com.miniagent.config.repository.ChatConversationRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:conversation-projects;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ConversationProjectService.class, AgentProjectAuthorizationService.class, DatabaseConversationStore.class})
class ConversationProjectServicePersistenceTest {
    @Autowired private ConversationProjectService conversationProjects;
    @Autowired private AgentProjectAuthorizationService projectAuthorization;
    @Autowired private AgentProjectRepository projects;
    @Autowired private AgentProjectMemberRepository members;
    @Autowired private ChatConversationRepository conversations;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private UserRepository users;
    @MockBean private ProjectAuthorizationSyncService authorizationSync;

    @AfterEach
    void clearOwner() {
        OwnerContext.clearCurrentTenant();
        OwnerContext.clearCurrentUser();
    }

    @Test
    void bindingIsPersistedAndUnbindingRemovesTrustedProjectContext() {
        saveProject("project-a", "21", 9L);
        saveConversation("session-a", 9L);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            conversationProjects.bind(9L, "session-a", "project-a");
        }
        assertEquals("project-a", conversationProjects.resolveAuthorized(9L, "21", "session-a"));

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            conversationProjects.unbind(9L, "session-a");
        }
        assertNull(conversationProjects.resolveAuthorized(9L, "21", "session-a"));
        assertNull(conversations.findById("session-a").orElseThrow().getProjectId());
    }

    @Test
    void removingMemberImmediatelyInvalidatesExistingSessionBinding() {
        saveProject("project-member", "21", 9L);
        saveConversation("session-member", 10L);
        saveMember("project-member", 10L);
        try (var ignored = OwnerContext.bind(new OwnerContext(10L, "21"))) {
            conversationProjects.bind(10L, "session-member", "project-member");
            assertEquals("project-member", conversationProjects.resolveAuthorized(10L, "21", "session-member"));
        }

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            projectAuthorization.removeMember("project-member", 10L);
        }
        assertThrows(AccessDeniedException.class,
                () -> conversationProjects.resolveAuthorized(10L, "21", "session-member"));
        assertEquals("project-member", conversations.findById("session-member").orElseThrow().getProjectId(),
                "persisted binding remains auditable but is no longer authorized");
    }

    @Test
    void bindingRejectsProjectFromAnotherTenantAndDoesNotPersistIt() {
        saveProject("project-other-tenant", "22", 9L);
        saveConversation("session-cross-tenant", 9L);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            assertThrows(AccessDeniedException.class,
                    () -> conversationProjects.bind(9L, "session-cross-tenant", "project-other-tenant"));
        }
        assertNull(conversations.findById("session-cross-tenant").orElseThrow().getProjectId());
        assertNull(conversationProjects.resolveAuthorized(9L, "21", "session-cross-tenant"));
    }

    private void saveProject(String projectId, String tenantId, Long ownerId) {
        AgentProjectEntity project = new AgentProjectEntity();
        project.setProjectId(projectId);
        project.setTenantId(tenantId);
        project.setOwnerUserId(ownerId);
        project.setProjectName(projectId);
        project.setCreatedAt(LocalDateTime.now());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> projects.saveAndFlush(project));
    }

    private void saveConversation(String sessionId, Long userId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(sessionId);
        conversation.setUserId(userId);
        conversation.setTitle(sessionId);
        conversation.setCreatedAt(System.currentTimeMillis());
        conversation.setUpdatedAt(System.currentTimeMillis());
        conversation.setDeleted(false);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> conversations.saveAndFlush(conversation));
    }

    private void saveMember(String projectId, Long userId) {
        AgentProjectMemberEntity member = new AgentProjectMemberEntity();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole("MEMBER");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> members.saveAndFlush(member));
    }
}
