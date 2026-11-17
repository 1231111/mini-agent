package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.config.cloud.CloudMembershipClient;
import com.miniagent.config.cloud.CloudMembershipClient.MembershipView;
import com.miniagent.config.cloud.CloudMembershipClient.OrderView;
import com.miniagent.config.cloud.CloudMembershipClient.PlanView;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.security.CurrentUser;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会员中心的数据端点：本服务只做鉴权与转发，业务与数据都在账号服务。
 *
 * <h3>这个类存在的唯一理由：userId 不能由调用方给</h3>
 *
 * <p>账号服务的会员端点要求服务间密钥，而它<b>不签 token</b>、没有会话概念，
 * 所以那边收到的 {@code userId} 完全是调用方说了算的。于是"这个 userId 是不是就是你"
 * 这件事只能在<b>这一层</b>判掉 —— 而本层手里有用户的 Bearer token。
 *
 * <p>因此涉及用户身份的端点都从 {@link CurrentUser} 取本地 {@code userId}，
 * 独立本地库再按当前用户行的 {@code externalId} 映射为云端身份；仅共享库可复用主键。
 * <b>请求体里刻意没有 userId 字段</b>。不是"忽略了它"，是压根没有这个字段可填：
 * 只要契约上不给，将来也不会有人"顺手"把它接上。
 * 一旦哪天有人在请求体里加了 userId 并透传下去，任何人都能查到别人的会员和订单，
 * 而代码看起来只是多传了一个参数。
 *
 * <p>响应里的 {@code userId} 是账号服务回显的，只用于校验转发是否正确，前端不应依赖它。
 *
 * <h3>为什么和 {@code /api/tokens} 一样走 200 + success:false</h3>
 *
 * <p>上游失败的转换在 {@code GlobalExceptionHandler#handleCloudAccount} 里，
 * 理由见那里的说明。
 */
@RestController
@RequestMapping("/api/membership")
public class MembershipController {

    @Autowired
    private CloudMembershipClient membership;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private UserRepository users;

    @Value("${agent.auth.cloud.shared-database:false}")
    private boolean sharedDatabase;

    /** 下单请求。只有套餐代码 —— 见类注释，userId 不在契约里。 */
    public record CreateOrderRequest(String planCode) {
    }

    /** 可售套餐，用于渲染价格表。 */
    @GetMapping(value = "/plans", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<PlanView>> plans() {
        // 这一条其实不依赖 userId，但仍然要求登录：
        // 价格表是"给会员中心看的"，放在免登录路径上等于开放了一个无需凭据的对外接口，
        // 而这会让"注册页顺便展示价格"变成一个顺手加上的功能，之后很难收回来。
        return ApiResponse.ok(membership.plans());
    }

    /** 当前生效的会员。没有生效中的订阅时返回 MEMBER_SUBSCRIPTION_NOT_FOUND。 */
    @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipView> current() {
        return ApiResponse.ok(membership.current(cloudUserId()));
    }

    /**
     * 下单。只落一条 PENDING 订单，不碰配额 —— 配额由支付回调触发。
     *
     * <p>没有支付渠道时这条路径是断的：订单会一直停在 PENDING。
     * 这是<b>有意的</b>，不是缺项 —— 在接入真实渠道之前，
     * 与其造一个假的"支付成功"，不如让状态诚实地停在那里。
     */
    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<OrderView> createOrder(@RequestBody(required = false) CreateOrderRequest req) {
        String planCode = req == null ? null : StringUtils.trimToNull(req.planCode());
        if (planCode == null) {
            // 本地先拦：让账号服务去处理 null 会得到"套餐「null」不存在"，
            // 那句话里带着 null，读日志的人会先去怀疑反序列化。
            return ApiResponse.fail(ErrorCode.MEMBER_PLAN_NOT_FOUND, "缺少 planCode");
        }
        return ApiResponse.ok(membership.createOrder(cloudUserId(), planCode));
    }

    /** Local and cloud primary keys are different identity spaces outside shared-database mode. */
    private Long cloudUserId() {
        Long localUserId = currentUser.userId();
        if (localUserId == null || localUserId <= 0) {
            throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (sharedDatabase) {
            // Shared-database login validates that this session is the authoritative cloud row.
            return localUserId;
        }
        String externalId = users.findExternalIdById(localUserId).orElse(null);
        if (externalId != null) {
            try {
                long cloudUserId = Long.parseLong(externalId.trim());
                if (cloudUserId > 0) {
                    return cloudUserId;
                }
            } catch (NumberFormatException ignored) {
                // A malformed mapping must never fall back to a possibly unrelated cloud user.
            }
        }
        throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,
                "当前账号未关联有效的云端身份，请重新登录");
    }
}
