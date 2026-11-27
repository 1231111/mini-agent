package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskContinuationControllerTest {
    private AgentChatApplicationService chats;
    private SessionAuthorizationService authorization;
    private MockMvc mvc;
    private static final String PATH = "/api/conversations/owned-session/todos/2/continue";
    private static final AuthenticatedUser USER = new AuthenticatedUser(7L, 3L, "owner", UserRole.USER);

    @BeforeEach void setUp() {
        chats = mock(AgentChatApplicationService.class);
        authorization = mock(SessionAuthorizationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskContinuationController(chats, authorization)).build();
    }

    @Test void requiresAnAuthenticatedPrincipal() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionId\":\"original-execution\",\"version\":5}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(chats, authorization);
    }

    @Test void requiresOwnerBeforeDispatchingAControl() throws Exception {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(authorization).requireOwner(7L, "owned-session");
        mvc.perform(post(PATH).requestAttr(JwtSessionService.ATTR_PRINCIPAL, USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionId\":\"original-execution\",\"version\":5}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(chats);
    }

    @Test void bodyRequiresExplicitExecutionAndNonnegativeVersion() throws Exception {
        for (String body : new String[]{"{}", "{\"executionId\":\"\",\"version\":5}",
                "{\"executionId\":\"e\"}", "{\"executionId\":\"e\",\"version\":-1}"}) {
            mvc.perform(post(PATH).requestAttr(JwtSessionService.ATTR_PRINCIPAL, USER)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(chats);
    }

    @Test void passesTypedIdentityToTheControlEntryWithoutCreatingAChatMessage() throws Exception {
        when(chats.confirmAndContinue(7L, "owned-session", 2, "original-execution", 5L))
                .thenReturn(new SseEmitter(0L));
        mvc.perform(post(PATH).requestAttr(JwtSessionService.ATTR_PRINCIPAL, USER)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"executionId\":\"original-execution\",\"version\":5}"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(authorization).requireOwner(7L, "owned-session");
        verify(chats).confirmAndContinue(7L, "owned-session", 2, "original-execution", 5L);
        verifyNoMoreInteractions(chats);
    }
}
