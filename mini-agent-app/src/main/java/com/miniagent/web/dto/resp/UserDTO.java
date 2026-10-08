package com.miniagent.web.dto.resp;

import com.miniagent.config.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private Long userId;

    private String username;

    private String displayName;

    private Long tenantId;

    private UserRole role = UserRole.USER;

    /** 登录/注册成功时签发的 JWT。其它场景不填。 */
    private String token;
}
