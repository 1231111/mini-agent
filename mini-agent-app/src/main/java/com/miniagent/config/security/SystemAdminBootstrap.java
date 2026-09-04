package com.miniagent.config.security;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.regex.Pattern;

/** Creates the first system administrator without shipping default credentials. */
@Component
public class SystemAdminBootstrap implements ApplicationRunner {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{2,49}");

    @Autowired
    private UserRepository users;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${agent.auth.bootstrap-admin.username:}")
    private String username;
    @Value("${agent.auth.bootstrap-admin.password:}")
    private String password;
    @Value("${agent.auth.bootstrap-admin.display-name:System Administrator}")
    private String displayName;
    @Value("${agent.auth.bootstrap-admin.required:false}")
    private boolean required;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.countEffectiveByRole(UserRole.SYSTEM_ADMIN) > 0) {
            return;
        }
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            if (!required) {
                return;
            }
            throw new IllegalStateException(
                    "No system administrator exists. Set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD.");
        }
        if (password.length() < 16) {
            throw new IllegalStateException("Bootstrap administrator password must contain at least 16 characters");
        }
        if (!USERNAME.matcher(username.trim()).matches()) {
            throw new IllegalStateException("Bootstrap administrator username must be 3-50 safe characters");
        }

        String normalizedUsername = username.trim();
        Optional<User> existing = users.findByUsername(normalizedUsername);
        if (existing.isPresent() && existing.get().getRole() != UserRole.SYSTEM_ADMIN) {
            throw new IllegalStateException(
                    "Bootstrap username already belongs to a non-system account; refusing automatic privilege escalation");
        }

        User user = existing.orElseGet(User::new);
        Tenant tenant;
        if (existing.isPresent()) {
            tenant = tenants.findById(user.getTenantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bootstrap system administrator references a missing tenant"));
        } else {
            tenant = tenants.findBySlug("system")
                    .orElseGet(() -> {
                        Tenant value = new Tenant();
                        value.setSlug("system");
                        value.setName("System");
                        value.setEnabled(true);
                        return tenants.save(value);
                    });
        }
        if (!tenant.isEnabled()) {
            tenant.setEnabled(true);
            tenant = tenants.save(tenant);
        }

        user.setUsername(normalizedUsername);
        user.setDisplayName(StringUtils.abbreviate(displayName, 100));
        user.setPasswordHash(passwordEncoder.encode(password));
        if (existing.isEmpty()) {
            user.setTenantId(tenant.getId());
        }
        user.setRole(UserRole.SYSTEM_ADMIN);
        user.setEnabled(true);
        users.save(user);
    }
}
