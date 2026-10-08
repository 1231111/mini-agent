package com.miniagent.web.dto.resp;

/** POST /api/todo/confirm */
public record TodoConfirmDTO(int id, boolean confirmed) {
}
