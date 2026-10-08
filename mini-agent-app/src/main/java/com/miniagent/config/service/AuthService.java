package com.miniagent.config.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Value("${agent.auth.password-min-length:12}")
    private int passwordMinLength;
    @Value("${agent.auth.registration-enabled:true}")
    private boolean registrationEnabled;

    /**
     * 注册结果：成功带 user，失败带具体 ErrorCode 与可读细节。
     *
     * <p>不用 {@code Optional<User>} 表达失败 —— 该类型只能说明「没成功」，
     * 说不了「为什么没成功」。一旦用它，调用方拿到空值时已无从分辨是密码太短、
     * 用户名为空还是真的重名，只能把任何失败都说成同一句话。
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

    public RegisterResult register(String username, String password, String displayName) {
        // 先归一，再判定：存在性检查与落库必须用同一个值。
        // 原先 existsByUsername(username) 查原值、setUsername(username.trim()) 存 trim 值，
        // 导致 " alice " 绕过重名检查后撞唯一约束，抛成 500。
        String name = StringUtils.trimToEmpty(username);

        if (!registrationEnabled) {
            return RegisterResult.fail(ErrorCode.AUTH_REGISTER_DISABLED, "注册功能已关闭");
        }
        if (StringUtils.isBlank(name)) {
            return RegisterResult.fail(ErrorCode.AUTH_USERNAME_INVALID, "用户名不能为空");
        }
        if (StringUtils.isEmpty(password)) {
            return RegisterResult.fail(ErrorCode.AUTH_PASSWORD_REQUIRED, "密码不能为空");
        }
        if (password.length() < passwordMinLength) {
            return RegisterResult.fail(ErrorCode.AUTH_PASSWORD_TOO_SHORT,
                    "密码至少 " + passwordMinLength + " 位，当前 " + password.length() + " 位");
        }
        if (userRepository.existsByUsername(name)) {
            return RegisterResult.fail(ErrorCode.AUTH_USER_EXISTS, "用户名「" + name + "」已被占用");
        }
        // Find or create the default "system" tenant for new user registration
        Tenant tenant = tenants.findBySlug("system")
                .orElseGet(() -> {
                    Tenant value = new Tenant();
                    value.setSlug("system");
                    value.setName("System");
                    value.setEnabled(true);
                    return tenants.save(value);
                });

        User user = new User();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(StringUtils.isNotBlank(displayName) ? displayName.trim() : name);
        user.setTenantId(tenant.getId());
        try {
            // saveAndFlush 让唯一约束冲突在本方法内抛出，而不是拖到事务提交后变成 500
            return RegisterResult.ok(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // 两个请求同时通过上面的存在性检查时，唯一约束是最后一道防线
            return RegisterResult.fail(ErrorCode.AUTH_USER_EXISTS, "用户名「" + name + "」已被占用");
        }
    }

    @Transactional
    public Optional<User> login(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()));
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}
