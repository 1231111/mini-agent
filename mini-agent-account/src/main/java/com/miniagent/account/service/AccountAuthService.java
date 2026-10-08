package com.miniagent.account.service;

import com.miniagent.account.entity.Tenant;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.TenantRepository;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.common.ErrorCode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * 注册与登录校验。
 *
 * <p>它由云端 agent 的 {@code AuthService} 迁移而来，迁移时改掉了三个缺陷，
 * 每处都写在对应方法的注释里：共用 system 租户、不校验用户名长度、
 * 登录不校验账号启用状态。
 *
 * <p><b>本服务不签发 token。</b>登录成功只返回"这个用户名+密码对应哪个身份"，
 * 由调用方（云端 agent 或客户机本地后端）自己签会话。见 {@code AccountApplication} 的说明。
 */
@Service
public class AccountAuthService {

    private static final Logger log = LoggerFactory.getLogger(AccountAuthService.class);

    /** 与 {@code users.username} 的列宽一致（V1 baseline 里是 VARCHAR(50)）。 */
    private static final int USERNAME_MAX_LENGTH = 50;

    @Autowired
    private UserRepository users;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MembershipService membership;

    @Value("${agent.account.password-min-length:8}")
    private int passwordMinLength;
    @Value("${agent.account.registration-enabled:true}")
    private boolean registrationEnabled;

    /**
     * 注册结果：成功带 user，失败带具体 ErrorCode 与可读细节。
     *
     * <p>不用 {@code Optional<User>} 表达失败 —— 那个类型只能说"没成功"，
     * 说不了"为什么没成功"。调用方拿到空值时已无从分辨是密码太短、用户名不合法
     * 还是真的重名，只能把任何失败都说成同一句话。
     */
    public record RegisterResult(User user, ErrorCode error, String detail) {
        public static RegisterResult ok(User user) {
            return new RegisterResult(user, null, null);
        }

        public static RegisterResult fail(ErrorCode error, String detail) {
            return new RegisterResult(null, error, detail);
        }

        public boolean success() {
            return Objects.nonNull(user);
        }
    }

    /**
     * 注册。
     *
     * <p>顺序上有两处不能调换：
     * <ol>
     *   <li><b>长度校验必须在存在性检查之前。</b>超长用户名会让 INSERT 撞列宽限制，
     *       抛出的 {@code DataIntegrityViolationException} 会被下面的 catch 说成
     *       "用户名已被占用" —— 用户明明没重名，却被告知重名，而且反复改也改不好。</li>
     *   <li><b>先落 tenant 再落 user。</b>{@code users.tenant_id} 是 NOT NULL 且有外键
     *       指向 tenants，反过来必然违反约束。</li>
     * </ol>
     */
    @Transactional
    public RegisterResult register(String username, String password, String displayName) {
        // 先归一，再判定：存在性检查与落库必须用同一个值。
        // 否则 " alice " 会绕过重名检查，然后撞唯一约束抛成 500。
        String name = StringUtils.trimToEmpty(username);

        if (!registrationEnabled) {
            return RegisterResult.fail(ErrorCode.AUTH_REGISTER_DISABLED, "注册功能已关闭");
        }
        if (StringUtils.isBlank(name)) {
            return RegisterResult.fail(ErrorCode.AUTH_USERNAME_INVALID, "用户名不能为空");
        }
        if (name.length() > USERNAME_MAX_LENGTH) {
            return RegisterResult.fail(ErrorCode.AUTH_USERNAME_INVALID,
                    "用户名最长 " + USERNAME_MAX_LENGTH + " 个字符，当前 " + name.length() + " 个");
        }
        if (StringUtils.isEmpty(password)) {
            return RegisterResult.fail(ErrorCode.AUTH_PASSWORD_REQUIRED, "密码不能为空");
        }
        if (password.length() < passwordMinLength) {
            return RegisterResult.fail(ErrorCode.AUTH_PASSWORD_TOO_SHORT,
                    "密码至少 " + passwordMinLength + " 位，当前 " + password.length() + " 位");
        }
        if (users.existsByUsername(name)) {
            return RegisterResult.fail(ErrorCode.AUTH_USER_EXISTS, "用户名「" + name + "」已被占用");
        }

        Tenant tenant = createPersonalTenant(name);

        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(StringUtils.isNotBlank(displayName) ? displayName.trim() : name);
        user.setTenantId(tenant.getId());
        try {
            // saveAndFlush 让唯一约束冲突在本方法内抛出，而不是拖到事务提交后变成 500
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 两个请求同时通过上面的存在性检查时，唯一约束是最后一道防线。
            // 走到这里说明是同一 user 名的并发注册 —— 前一条校验已经排除了长度问题。
            return RegisterResult.fail(ErrorCode.AUTH_USER_EXISTS, "用户名「" + name + "」已被占用");
        }

        // 落一条 free 订阅：让"我的会员等级是什么"永远有答案，
        // 而不是把"查不到订阅"这种正常状态推给每个调用方各自处理。
        membership.startFreeSubscription(user.getId(), tenant.getId());
        return RegisterResult.ok(user);
    }

    /**
     * 登录校验。
     *
     * <p>三种失败（用户不存在 / 密码错 / 账号被禁用）对外都返回同一个结果，
     * 由调用方统一转成 {@code AUTH_LOGIN_FAILED}。分开返回等于提供了一个
     * 用户名枚举接口：攻击者可以逐个个试出哪些用户名存在、哪些被禁用了。
     * 真实的失败原因记在服务端日志里 —— 那是运维该看的地方，不是响应体。
     */
    @Transactional(readOnly = true)
    public Optional<User> login(String username, String password) {
        String name = StringUtils.trimToEmpty(username);
        // 必须先判空密码，不能省：BCryptPasswordEncoder.matches(null, hash) 抛
        // IllegalArgumentException，而它会被全局兜底处理器变成 HTTP 500「系统内部错误」。
        // 于是一个只填了用户名的请求就成了一个 500 —— 前端把它当成服务端故障去重试，
        // 而真正的原因是请求缺了一个字段。
        if (StringUtils.isEmpty(password)) {
            log.info("登录失败：未提供密码 username={}", name);
            return Optional.empty();
        }
        Optional<User> found = users.findByUsername(name);
        if (found.isEmpty()) {
            log.info("登录失败：账号不存在 username={}", name);
            return Optional.empty();
        }
        User user = found.get();
        if (!user.isEnabled()) {
            log.warn("登录失败：账号已被禁用 username={} userId={}", name, user.getId());
            return Optional.empty();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.info("登录失败：密码不匹配 username={} userId={}", name, user.getId());
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /**
     * 给新用户建专属租户。
     *
     * <p><b>这里改掉了迁移前的一个缺陷</b>：原 {@code AuthService.register} 把所有自助注册
     * 的人塞进同一个 {@code system} 租户。而配额上限就存在 tenants 上
     * （云端 agent 的 {@code DbTenantTokenQuota.check/consume(tenantId)} 读的正是这一列），
     * 于是所有用户共享一个日 token 池：谁用得多，别人就没得用。
     * 而且"按人分档"这件事在那种结构下根本无法表达。
     *
     * <p>额度不在这里定。新租户的 {@code daily_token_limit} 先留 0，
     * 紧接着由 {@link MembershipService#startFreeSubscription} 按 free 套餐写实。
     * <b>刻意不给一个"看起来合理"的默认数字</b>：那样默认值会变成一条没人记得的隐性承诺，
     * 改套餐时也不会有人想到去改它。缺 free 套餐就直接抛，让配置问题当场暴露。
     */
    private Tenant createPersonalTenant(String username) {
        Tenant tenant = new Tenant();
        // slug 唯一对应一个用户，且能一眼看出归属。username 本身已通过唯一性检查，
        // 长度上限 50 加前缀 2 位 = 52，仍在 tenants.slug 的 64 之内。
        tenant.setSlug("u-" + username);
        tenant.setName(username);
        tenant.setEnabled(true);
        tenant.setDailyTokenLimit(0);
        return tenants.saveAndFlush(tenant);
    }
}
