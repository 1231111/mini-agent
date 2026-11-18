package com.miniagent.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.Size;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class FileAttachment {
    @Size(max = 255)
    private String filename;
    @Size(max = 128)
    private String mimeType;
    @Size(max = 32_000_000)
    private String base64;
}
