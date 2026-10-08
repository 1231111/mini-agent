package com.miniagent.web.dto.resp;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/upload */
public record UploadResultDTO(
        String filePath,
        String filename,
        String mimeType,
        Long fileSize,
        @JsonInclude(JsonInclude.Include.NON_NULL) String kind,
        @JsonInclude(JsonInclude.Include.NON_NULL) String extractedTextPath) {
}
