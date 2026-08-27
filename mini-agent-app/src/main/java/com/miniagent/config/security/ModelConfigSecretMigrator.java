package com.miniagent.config.security;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.repository.UserModelConfigRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Encrypts legacy plaintext model keys and validates existing ciphertext during startup. */
@Component
public class ModelConfigSecretMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ModelConfigSecretMigrator.class);

    private final UserModelConfigRepository repository;
    private final SecretCryptoService crypto;

    public ModelConfigSecretMigrator(UserModelConfigRepository repository,
                                     SecretCryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!crypto.isEnabled()) {
            return;
        }
        int migrated = 0;
        for (UserModelConfig config : repository.findAll()) {
            String stored = config.getCustomApiKey();
            if (StringUtils.isBlank(stored)) {
                continue;
            }
            if (crypto.isEncrypted(stored)) {
                crypto.decrypt(stored);
                continue;
            }
            config.setCustomApiKey(crypto.encrypt(stored));
            migrated++;
        }
        if (migrated > 0) {
            log.info("Encrypted {} legacy model credential(s)", migrated);
        }
    }
}
