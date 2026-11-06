package com.miniagent.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 云端账号服务。
 *
 * <p>它是账号的唯一权威：注册、登录校验、会员等级、订单与充值都在这里。
 * 云端 agent（{@code mini-agent-app}，prod 档）与客户机本地后端都只是它的调用方。
 *
 * <h3>两条必须守住的边界</h3>
 *
 * <p><b>1. 本服务不签发会话 token。</b>登录成功回答"这个用户名+密码对应哪个身份"
 * （{@code data.userId} / {@code username} / {@code displayName} / {@code role}），
 * 另外附带一枚专供 memory-service 的 memory access token —— 它只用于访问记忆服务，
 * 不能当桌面端的本地会话凭证。桌面端本地会话仍由客户机本地后端自己签发。
 * 这样 HS256 会话密钥不必在两个服务之间同步，少一处可以配错、且配错会静默降级的地方。
 *
 * <p><b>2. 组件扫描范围只到 {@code com.miniagent.account}。</b>本服务依赖
 * {@code mini-agent-common}，而那个模块里混着 {@code SharedMilvusClient}、
 * {@code LocalOnnxEmbeddingModel} 等带外部依赖的组件。扫描范围一旦放宽到
 * {@code com.miniagent}，它们会被一起装配，账号服务会在启动时因为连不上 Milvus 或
 * 找不到 ONNX 模型而失败 —— 而报错信息会指向一个与本服务毫无关系的东西。
 * 默认扫描根就是本类所在包，不要给它加 scanBasePackages。
 */
@SpringBootApplication
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }

    /**
     * 令牌签发与校验统一取这个时钟。
     *
     * <p>{@code MemoryAccessTokenService} 通过构造器注入 {@link Clock}，而不是在方法里
     * 直接调 {@code Instant.now()}：测试可以塞固定时钟去验证过期边界，不用 sleep 等时间。
     *
     * <p>用 UTC 而非系统默认时区：令牌里存的是 epoch 秒，与时区无关；用系统时区只在
     * 部署机器的 TZ 被改过时引入无意义差异。memory-service 侧同样取 {@code systemUTC()}。
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
