package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.config.cloud.CloudMembershipClient;
import com.miniagent.config.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前用户的每日额度视图。身份来自签名会话，不接受外部传 userId。
 *
 * <p>数据源是账号服务 —— 本地不再保存用户日用量，只保留账号服务给出的结论。
 * 额度只按会员套餐计算，没有租户级额度。</p>
 */
@RestController
@RequestMapping(value = "/api/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserTokenQuotaController {

    @Autowired private CurrentUser currentUser;
    @Autowired private ObjectProvider<CloudMembershipClient> clients;

    @GetMapping("/token-quota")
    public ApiResponse<Map<String, Object>> tokenQuota() {
        Map<String, Object> out = new LinkedHashMap<>();
        CloudMembershipClient client = clients.getIfAvailable();
        if (client == null || !client.available()) {
            // 桌面形态没有配置账号服务：没有额度体系，界面按「不限」展示。
            out.put("availableTokens", null);
            out.put("source", "none");
            return ApiResponse.ok(out);
        }
        try {
            CloudMembershipClient.EntitlementView view = client.entitlement(currentUser.userId());
            // remainingTokens 为 -1 表示不限；前端用 null 区分「不限」和「0 剩余」。
            out.put("availableTokens", view.remainingTokens() < 0 ? null : view.remainingTokens());
            out.put("dailyLimitTokens", view.dailyLimitTokens());
            out.put("usedTokens", view.usedTokens());
            out.put("resetAt", view.resetAt());
            out.put("source", "account");
        } catch (RuntimeException unreachable) {
            // 查不到时按「不限」展示，和 TokenQuotaFilter 的 fail-open 保持一致：
            // 账号服务抖动不该让界面显示成「额度用完了」。
            out.put("availableTokens", null);
            out.put("source", "unreachable");
        }
        return ApiResponse.ok(out);
    }
}
