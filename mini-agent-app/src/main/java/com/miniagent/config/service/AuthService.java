package com.miniagent.config.service;

import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Register a new user.
     * @return the created User, or empty if username already exists
     */
    public Optional<User> register(String username, String password, String displayName) {
        if (userRepository.existsByUsername(username)) {
            return Optional.empty();
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(hashPassword(password));
        user.setDisplayName(displayName != null ? displayName : username);
        return Optional.of(userRepository.save(user));
    }

    /**
     * Authenticate user by username and password.
     * @return the User if credentials are valid, empty otherwise
     */
    public Optional<User> login(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(user -> user.getPasswordHash().equals(hashPassword(password)));
    }

    /**
     * Get user by ID
     */
    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    /**
     * Get user by username
     */
    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Hash password using SHA-256
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
