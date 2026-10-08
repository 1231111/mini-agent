package com.miniagent.web.dto.resp;

/** POST /api/admin/users/{id}/revoke-sessions */
public record RevokedSessionsDTO(int revoked) {
}
