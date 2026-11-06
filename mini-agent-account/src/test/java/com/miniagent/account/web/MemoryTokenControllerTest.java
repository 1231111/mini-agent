package com.miniagent.account.web;

import com.miniagent.account.entity.User;
import com.miniagent.account.service.MemoryAccessTokenService;
import com.miniagent.memory.contract.MemoryAccessToken;
import com.miniagent.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class MemoryTokenControllerTest {
    private final MemoryAccessTokenService tokens = mock(MemoryAccessTokenService.class);
    private final MemoryTokenController controller = new MemoryTokenController(tokens);

    @Test
    void invalidServiceKeyReturnsHttp403() {
        var request = new MemoryTokenController.IntrospectRequest("ma1.token");
        var servletRequest = new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.addHeader("X-Account-Internal-Key", "wrong");
        when(tokens.introspect("ma1.token", "wrong")).thenThrow(new SecurityException());

        var response = controller.introspect(request, servletRequest);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(false, response.getBody().isSuccess());
    }

    @Test
    void validRefreshCredentialReturnsFreshAccessTokenWithoutInternalServiceKey() {
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        user.setTenantId(9L);
        user.setRole(com.miniagent.account.entity.UserRole.USER);
        var issued = new MemoryAccessTokenService.IssuedCredential(user, "ma1.fresh", "refresh-secret");
        when(tokens.refresh("refresh-secret")).thenReturn(issued);

        var response = controller.refresh(new MemoryTokenController.RefreshRequest("refresh-secret"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ma1.fresh", response.getBody().getData().memoryToken());
        assertEquals("refresh-secret", response.getBody().getData().memoryRefreshCredential());
        verify(tokens).refresh("refresh-secret");
    }

    @Test
    void invalidRefreshCredentialReturnsHttp401() {
        when(tokens.refresh("revoked")).thenThrow(new IllegalArgumentException());

        var response = controller.refresh(new MemoryTokenController.RefreshRequest("revoked"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(tokens).refresh("revoked");
    }

    @Test
    void invalidTokenReturnsHttp401() {
        var request = new MemoryTokenController.IntrospectRequest("ma1.invalid");
        var servletRequest = new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.addHeader("X-Account-Internal-Key", "correct");
        when(tokens.introspect("ma1.invalid", "correct")).thenThrow(new IllegalArgumentException());

        var response = controller.introspect(request, servletRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(false, response.getBody().isSuccess());
    }
}
