package com.miniagent.config.security;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.repository.UserModelConfigRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Encrypts legacy plaintext model keys and validates existing ciphertext during startup.
 *
 * <p><b>逐行容错，不再因为一行坏数据拒绝启动。</b>此前这里对每一行 {@code decrypt()} 都不捕获，
 * 于是换过（或丢了）{@code MODEL_CONFIG_ENCRYPTION_KEY} 的部署会直接起不来 ——
 * 一个用户行的密文解不开，整个平台停摆，而且恢复路径只有"手工改库"。
 * 现在解不开的行会被跳过并汇总成一条 ERROR 日志（含 userId 与处置建议）：
 * 受影响用户重新保存一次密钥即可，其余用户不受影响。</p>
 *
 * <p>注意：读取侧（{@code UserModelConfigService.resolve}）对解不开的行仍然会明确报错 ——
 * 跳过是为了让服务能起来，不是为了把坏凭据当成好凭据用。</p>
 */
@Component
public class ModelConfigSecretMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ModelConfigSecretMigrator.class);

    @Autowired
    private UserModelConfigRepository repository;
    @Autowired
    private SecretCryptoService crypto;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!crypto.isEnabled()) {
            return;
        }
        int migrated = 0;
        List<Long> undecryptable = new ArrayList<>();
        for (UserModelConfig config : repository.findAll()) {
            String stored = config.getCustomApiKey();
            if (StringUtils.isBlank(stored)) {
                continue;
            }
            if (crypto.isEncrypted(stored)) {
                try {
                    crypto.decrypt(stored);
                } catch (RuntimeException e) {
                    undecryptable.add(config.getUserId());
                }
                continue;
            }
            config.setCustomApiKey(crypto.encrypt(stored));
            migrated++;
        }
        if (migrated > 0) {
            log.info("Encrypted {} legacy model credential(s)", migrated);
        }
        if (!undecryptable.isEmpty()) {
            log.error("有 {} 个用户的模型密钥无法解密（userId={}）：当前 MODEL_CONFIG_ENCRYPTION_KEY 与"
                            + "加密时使用的不一致。服务会继续启动，但这些用户的模型调用会明确报错，"
                            + "需要他们重新保存一次模型密钥（或用旧密钥恢复）。",
                    undecryptable.size(), undecryptable);
        }
    }
}
