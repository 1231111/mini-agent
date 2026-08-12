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
    /** 已上传音/视频，服务端读盘组装 AudioContent / VideoContent */
    private List<MediaRef> mediaRefs;
    private String role;  // 角色选择：tester/developer/pm/designer/security/ops/dba/architect/tech_writer
    /** 权限模式：default | plan | accept_edits | ask */
    private String permissionMode;

}
