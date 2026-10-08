package com.miniagent.web.dto.resp;

import com.fasterxml.jackson.annotation.JsonInclude;

/** GET /api/auth-status */
public record AuthStatusDTO(
        boolean authenticated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long userId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String username,
        @JsonInclude(JsonInclude.Include.NON_NULL) String displayName) {

    public static AuthStatusDTO anonymous() {
        return new AuthStatusDTO(false, null, null, null);
    }

    public static AuthStatusDTO authenticated(Long userId, String username, String displayName) {
        return new AuthStatusDTO(true, userId, username, displayName);
    }
}
