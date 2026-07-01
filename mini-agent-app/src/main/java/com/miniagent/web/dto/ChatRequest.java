package com.miniagent.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatRequest {
    private String message;
    private String sessionId;
    private List<String> images;
    private List<FileAttachment> files;
    private List<FileRef> fileRefs;

}
