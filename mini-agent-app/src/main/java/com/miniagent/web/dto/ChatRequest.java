package com.miniagent.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatRequest {
    @Size(max = 20_000)
    private String message;
    private String sessionId;
    @Size(max = 16)
    private List<@Size(max = 32_000_000) String> images;
    @Size(max = 16)
    private List<@Valid FileAttachment> files;
    @Size(max = 32)
    private List<FileRef> fileRefs;
    /** 已上传音/视频，服务端读盘组装 AudioContent / VideoContent */
    @Size(max = 16)
    private List<MediaRef> mediaRefs;
    private String role;  // 角色选择：tester/developer/pm/designer/security/ops/dba/architect/tech_writer
    /** 权限模式：default | plan | accept_edits | ask */
    private String permissionMode;
    /** 待办确认：auto | dangerous */
    private String confirmPolicy;

}
