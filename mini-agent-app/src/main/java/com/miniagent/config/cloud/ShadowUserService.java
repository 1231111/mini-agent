package com.miniagent.config.cloud;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
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
import java.util.UUID;

/**
 * 云端身份 → 本地影子用户的落库部分。
 *
 * <p>为什么独立成一个类、而不是把方法放进 {@link CloudAccountService}：
 * 这里要事务，而云端 HTTP 调用<b>绝不能</b>在事务里。同一个类里自调用
 * {@code @Transactional} 方法会绕过 Spring 代理、注解直接失效 ——
 * 那样网络请求就被包进事务了，一次 10 秒超时会一直占着数据库连接和行锁。
 * 拆成两个 bean 是最不容易被人改错的做法。
 *
 * <h3>影子用户的三条规则</h3>
 *
 * <p><b>1. 角色不由云端决定。</b>云端角色描述的是云端权限域里你是管理员还是普通用户；
 * 本地这里 {@code SYSTEM_ADMIN} 意味着能改本机配置、看本机全部数据。
 * 两者不是同一个权限域，自动等同等于把云端的提权直接放大到设备上。
 * 所以默认一律建为 {@link UserRole#USER}；确实要让云端管理员在本地也当管理员，
 * 显式打开 {@code agent.auth.cloud.adopt-role=true}。
 *
 * <p><b>2. 绝不"接管"同名的本地账号。</b>如果本地已存在同名且 {@code externalId} 为 null 的行，
 * 那是另一个人的纯本地账号。把它绑到当前云端身份上，等于把前者的聊天记录和记忆交给后者。
 * 正确做法是让影子用户换个名字（{@code 用户名#外部id}），并在日志里说清原因。
 *
 * <p><b>3. 本地禁用的影子用户，登录时直接拒绝。</b>本机管理员禁用某人必须有效，
 * 不能被"云端说他是合法的"覆盖掉。
 *
 * <h3>共享库模式：{@code agent.auth.cloud.shared-database}</h3>
 *
 * <p>上面三条针对的是<b>客户机</b>场景：本机库与账号库是两个库，云端身份在本机没有行，
 * 所以必须造一行影子记录当锚点。
 *
 * <p>但云端 agent 自己不是这个场景 —— 它与账号服务{@linkplain #sharedDatabase 共用同一个库}，
 * 账号服务注册时已经写好了 {@code users} 行。此时若还走上面的"造影子行"，
 * 结果是在同一张表里为同一个人插第二条记录：{@code external_id} 指向前一条，
 * 租户却落在 {@code system} 上。而会员配额写在<b>前一条的租户</b>上，
 * agent 读的却是<b>后一条的租户</b> —— 表现就是"买了会员但额度没变"，
 * 且日志里一切正常。
 *
 * <p>所以共享库模式下不再映射，直接按 {@code userId} 读那一行本身。
 * 读不到即报错，不做兜底新建：那正是要防的重复行。
 */
@Service
public class ShadowUserService {

    private static final Logger log = LoggerFactory.getLogger(ShadowUserService.class);

    /** users.username 列宽 50，派生名必须能塞进去。 */
    private static final int USERNAME_MAX = 50;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${agent.auth.cloud.adopt-role:false}")
    private boolean adoptRole;

    /**
     * 本机库与账号库是否同一个库。
     *
     * <p>{@code false}（默认，客户机）：云端身份与本机数据分处两库，需要影子行做锚点。
     * <p>{@code true}（云端 agent）：与账号服务共用同一个库，账号行就是本机行，不能再映射。
     */
    @Value("${agent.auth.cloud.shared-database:false}")
    private boolean sharedDatabase;

    /**
     * 按云端身份找到（或新建）本地影子用户。
     *
     * @throws CloudAccountException 本地已存在该影子用户但被禁用（{@code AUTH_FORBIDDEN}）
     */
    @Transactional
    public User materialize(CloudUser cloudUser) {
        if (sharedDatabase) {
            return adoptSharedRow(cloudUser);
        }
        Optional<User> existing = userRepository.findByExternalIdForUpdate(cloudUser.externalId());
        if (existing.isPresent()) {
            return refresh(existing.get(), cloudUser);
        }
        return create(cloudUser);
    }

    /**
     * 共享库模式：账号服务返回的 userId <b>就是</b>本库这一行的主键，直接读它。
     *
     * <p>只同步显示名，不碰角色：角色由本机管理员决定这条规则在共享库场景下同样成立，
     * 而且在共享库场景下更要紧 —— 账号服务那份 {@code users.role} 和 agent 读的是同一列，
     * 若按云端角色回写，就等于任何注册用户都能把自己写成 {@link UserRole#SYSTEM_ADMIN}。
     */
    private User adoptSharedRow(CloudUser cloudUser) {
        Long id = parseNumericId(cloudUser.externalId());
        User user = userRepository.findById(id).orElseThrow(() -> new CloudAccountException(
                ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                "共享库模式（agent.auth.cloud.shared-database=true）下找不到 users.id=" + id
                        + "（账号服务返回的 userId=" + cloudUser.externalId() + "）。"
                        + "最常见的原因是 agent 与 account 没指向同一个库 —— "
                        + "请核对两者的 spring.datasource.url。"));

        if (!user.isEnabled()) {
            throw new CloudAccountException(ErrorCode.AUTH_FORBIDDEN,
                    "账号已被停用（users.id=" + id + "），请联系管理员");
        }

        if (cloudUser.displayName() != null
                && !Objects.equals(user.getDisplayName(), cloudUser.displayName())) {
            user.setDisplayName(cloudUser.displayName());
            return userRepository.save(user);
        }
        return user;
    }

    /**
     * 外部 id 转主键。
     *
     * <p>共享库模式下这里必须能转成数字 —— 因为它要当主键用。
     * 转不了说明账号服务换了 id 方案（比如改成 UUID），那是个需要显式处理的变更，
     * 不能靠"转失败就当 null 继续跑"糊过去：那样会退化成每次登录都新建一行。
     */
    private static Long parseNumericId(String externalId) {
        try {
            return Long.valueOf(externalId.trim());
        } catch (NumberFormatException e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "共享库模式要求账号服务返回数字 userId，实际收到「" + externalId + "」");
        }
    }

    private User refresh(User user, CloudUser cloudUser) {
        if (!user.isEnabled()) {
            throw new CloudAccountException(ErrorCode.AUTH_FORBIDDEN,
                    "本地已停用该账号（本地用户 id=" + user.getId() + "），请先在本机恢复");
        }
        boolean changed = false;
        // 显示名以云端为准：它就是"用户改了自己的昵称"这件事的载体。
        if (cloudUser.displayName() != null && !Objects.equals(user.getDisplayName(), cloudUser.displayName())) {
            user.setDisplayName(cloudUser.displayName());
            changed = true;
        }
        if (adoptRole) {
            UserRole role = parseRole(cloudUser.role());
            if (role != null && role != user.getRole()) {
                user.setRole(role);
                changed = true;
                log.info("影子用户角色随云端更新: 本地 id={} role={}", user.getId(), role);
            }
        }
        return changed ? userRepository.save(user) : user;
    }

    private User create(CloudUser cloudUser) {
        String username = cloudUser.username();
        if (userRepository.existsByUsername(username)) {
            String derived = deriveShadowUsername(cloudUser);
            log.warn("云端用户名「{}」在本机已被一个纯本地账号占用，影子用户改用「{}」——"
                    + "不接管同名本地账号，避免把前者的本地数据交给当前云端身份",
                    username, derived);
            username = derived;
        }

        User user = new User();
        user.setUsername(username);
        user.setExternalId(cloudUser.externalId());
        user.setDisplayName(cloudUser.displayName() != null ? cloudUser.displayName() : cloudUser.username());
        user.setTenantId(systemTenantId());
        user.setRole(adoptRole ? orDefault(parseRole(cloudUser.role())) : UserRole.USER);
        user.setEnabled(true);
        // password_hash 是 NOT NULL，而影子用户没有本地密码。
        // 塞一个随机串的 BCrypt 摘要，而不是空串或固定串：
        // 固定串意味着"知道这个固定串的人可以本地登录成这个账号"。
        // 随机串连我们自己都不知道，本地登录路径对影子用户恒为失败 —— 这正是我们要的。
        //
        // 注意长度：BCrypt 拒绝超过 72 字节的原文（实测报
        // CONFIG.02.01 "password cannot be more than 72 bytes"）。
        // 两个 UUID 拼起来是 73 字节，正好越界 —— 所以只能用一个（36 字节）。
        // 122 位熵对一个"永远不该被用来登录"的占位密码绰绰有余。
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));

        try {
            User saved = userRepository.saveAndFlush(user);
            log.info("已为云端账号建立本地影子用户: 本地 id={} 用户名={}",
                    saved.getId(), saved.getUsername());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // ux_users_external_id 兜底：两个并发请求同时新建同一个账号，
            // 后提交的那个在这里失败。这不是错误，重新读一次即可 ——
            // 对方已经建好了。
            return userRepository.findByExternalIdForUpdate(cloudUser.externalId())
                    .orElseThrow(() -> new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                            "影子用户创建失败：external_id 冲突但查不到既有行", e));
        }
    }

    /**
     * 给影子用户派生一个不冲突的用户名：{@code 原用户名#外部id}。
     *
     * <p>用外部 id 而不是序号，是因为它天然唯一且稳定 —— 同一台机器上重复登录
     * 会落到同一个名字上，不会每登录一次就多出一个 {@code name-2}。
     */
    private String deriveShadowUsername(CloudUser cloudUser) {
        String base = cloudUser.username() == null ? "cloud" : cloudUser.username();
        String suffix = "#" + cloudUser.externalId();

        // 后缀本身可能就超过列宽（外部 id 长达 128）。这种情况截断后缀，
        // 保住"用户名可辨认"这个更重要的属性。
        if (suffix.length() >= USERNAME_MAX) {
            suffix = suffix.substring(0, USERNAME_MAX - 1);
        }
        int maxBase = Math.max(1, USERNAME_MAX - suffix.length());
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        String candidate = base + suffix;

        // 理论上不会撞（后缀含唯一 id）。但 existsByUsername 是最后一道防线：
        // 万一云端把同一个人返回成两个不同 id，这里也不能抛 500 出去。
        String unique = candidate;
        for (int n = 2; n <= 20 && userRepository.existsByUsername(unique); n++) {
            String tail = "-" + n;
            unique = candidate.substring(0, Math.min(candidate.length(), USERNAME_MAX - tail.length())) + tail;
        }
        return unique;
    }

    /**
     * 影子用户统一挂在本地 {@code system} 租户下。
     *
     * <p>不用云端返回的 tenantId：那是云端租户体系里的 id，在本机没有对应行，
     * {@code SignedSessionFilter.resolvePrincipal} 会因为租户查不到而判成未认证。
     * 本地是单机单租户模型，云端身份只决定"你是谁"，不决定"本地数据归哪个租户"。
     */
    private Long systemTenantId() {
        Tenant tenant = tenants.findBySlug("system").orElseGet(() -> {
            Tenant value = new Tenant();
            value.setSlug("system");
            value.setName("System");
            value.setEnabled(true);
            return tenants.save(value);
        });
        return tenant.getId();
    }

    /** 云端角色名转本地枚举；不认识的取值返回 null（表示"保持不动"，而不是降级）。 */
    private static UserRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("云端返回了本地不认识的角色「{}」，本次不改变影子用户角色", role);
            return null;
        }
    }

    private static UserRole orDefault(UserRole role) {
        return role != null ? role : UserRole.USER;
    }
}
