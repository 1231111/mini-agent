package com.miniagent.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已上传、需原生多模态理解的音/视频引用（聊天 JSON 只传路径，不传 base64）。
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class MediaRef {
    private String filePath;
    private String filename;
    private String mimeType;
    private long fileSize;
    /** audio | video */
    private String kind;
}
