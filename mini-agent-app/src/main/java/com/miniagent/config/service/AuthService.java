package com.miniagent.config.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
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

    @Transactional
    public Optional<User> register(String username, String password, String displayName) {
        if (!registrationEnabled || StringUtils.isBlank(username) || Objects.isNull(password)
                || password.length() < passwordMinLength) {
            return Optional.empty();
        }
        if (userRepository.existsByUsername(username)) {
            return Optional.empty();
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
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(StringUtils.isNotBlank(displayName) ? displayName : username.trim());
        user.setTenantId(tenant.getId());
        return Optional.of(userRepository.save(user));
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
