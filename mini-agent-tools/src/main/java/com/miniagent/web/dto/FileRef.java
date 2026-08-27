package com.miniagent.web.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class FileRef {
    private String filePath;
    private String filename;
    private String mimeType;
    private long fileSize;
}