package com.miniagent.account.service;

import com.miniagent.account.config.PasswordEncoderConfig;
import com.miniagent.account.entity.User;
import com.miniagent.account.entity.MembershipPlan;
import com.miniagent.account.repository.MembershipPlanRepository;
import com.miniagent.account.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户机令牌的签发与校验。
 *
 * <p>三条不变量：</p>
 * <ol>
 *   <li><b>重新登录必然作废旧令牌。</b> 否则同一个账号能累积出无数把有效凭证，
 *       撤销就无从谈起。</li>
 *   <li><b>过期令牌必须解析失败。</b> 解析成功等于给了它无限寿命。</li>
 *   <li><b>库里只有哈希。</b> 泄露数据库不应等于泄露可用凭证。</li>
 * </ol>
 */
@DataJpaTest
@Import({AccountAuthService.class, MembershipService.class, AgentTokenService.class,
        PasswordEncoderConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agent.account.registration-enabled=true",
        "agent.account.password-min-length=8",
        "agent.account.bcrypt-strength=4"
})
class AgentTokenServiceTest {

    private static final String PASSWORD = "12345678";

    @Autowired
    private AgentTokenService tokens;
    @Autowired
    private AccountAuthService auth;
    @Autowired
    private UserRepository users;
    @Autowired
    private MembershipPlanRepository plans;

    /** 注册流程会给新用户开 free 订阅，没有这个套餐会直接抛异常。 */
    @BeforeEach
    void seedFreePlan() {
        if (plans.findByCode(MembershipService.FREE_PLAN_CODE).isEmpty()) {
            MembershipPlan plan = new MembershipPlan();
            plan.setCode(MembershipService.FREE_PLAN_CODE);
            plan.setName("免费版");
            plan.setDailyTokenLimit(200_000L);
            plan.setMaxConcurrentTasks(2);
            plan.setPriceCents(0L);
            plan.setCurrency("CNY");
            plan.setDurationDays(0);
            plan.setEnabled(true);
            plans.save(plan);
        }
    }

    private User newUser(String name) {
        AccountAuthService.RegisterResult result = auth.register(name, PASSWORD, null);
        assertThat(result.success()).as("测试前置数据准备失败").isTrue();
        return result.user();
    }

    @Test
    void issuedTokenResolvesToItsUser() {
        User user = newUser("tok_ok");
        AgentTokenService.Issued issued = tokens.rotate(user);

        assertThat(issued.token()).startsWith(AgentTokenService.PREFIX);
        assertThat(tokens.resolve(issued.token())).isEqualTo(user.getId());
    }

    @Test
    void onlyHashIsPersisted() {
        User user = newUser("tok_hash");
        AgentTokenService.Issued issued = tokens.rotate(user);

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getAgentTokenHash()).hasSize(64);
        assertThat(reloaded.getAgentTokenHash()).isNotEqualTo(issued.token());
        assertThat(reloaded.getAgentTokenExpiresAt()).isNotNull();
    }

    @Test
    void reloginInvalidatesThePreviousToken() {
        User user = newUser("tok_rotate");
        AgentTokenService.Issued first = tokens.rotate(user);
        AgentTokenService.Issued second = tokens.rotate(users.findById(user.getId()).orElseThrow());

        assertThat(tokens.resolve(first.token())).as("旧令牌必须失效").isNull();
        assertThat(tokens.resolve(second.token())).isEqualTo(user.getId());
    }

    @Test
    void expiredTokenIsRejected() {
        User user = newUser("tok_expired");
        AgentTokenService.Issued issued = tokens.rotate(user);

        User reloaded = users.findById(user.getId()).orElseThrow();
        reloaded.setAgentTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
        users.saveAndFlush(reloaded);

        assertThat(tokens.resolve(issued.token())).isNull();
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        User user = newUser("tok_noexp");
        AgentTokenService.Issued issued = tokens.rotate(user);

        User reloaded = users.findById(user.getId()).orElseThrow();
        reloaded.setAgentTokenExpiresAt(null);
        users.saveAndFlush(reloaded);

        // 没有过期时间的行不认：宁可让人重新登录，也不留一把永不过期的凭证。
        assertThat(tokens.resolve(issued.token())).isNull();
    }

    @Test
    void revokedTokenIsRejected() {
        User user = newUser("tok_revoke");
        AgentTokenService.Issued issued = tokens.rotate(user);
        tokens.revoke(user.getId());

        assertThat(tokens.resolve(issued.token())).isNull();
    }

    @Test
    void garbageIsRejected() {
        assertThat(tokens.resolve(null)).isNull();
        assertThat(tokens.resolve("")).isNull();
        assertThat(tokens.resolve("not-a-token")).isNull();
        assertThat(tokens.resolve("atk_made-up-value")).isNull();
    }
}
