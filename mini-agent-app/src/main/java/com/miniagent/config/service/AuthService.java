package com.miniagent.config.service;

import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<User> register(String username, String password, String displayName) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            return Optional.empty();
        }
        if (userRepository.existsByUsername(username)) {
            return Optional.empty();
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username.trim());
        return Optional.of(userRepository.save(user));
    }

    @Transactional
    public Optional<User> login(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(user -> matchesAndUpgrade(user, password));
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Accept BCrypt (new) and legacy unsalted SHA-256; upgrade SHA-256 hashes on successful login.
     */
    private boolean matchesAndUpgrade(User user, String password) {
        String stored = user.getPasswordHash();
        if (stored == null) return false;
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return passwordEncoder.matches(password, stored);
        }
        // Legacy SHA-256
        if (stored.equals(sha256(password))) {
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    private static String sha256(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
