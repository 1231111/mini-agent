package com.miniagent.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 云端账号服务。
 *
 * <p>它是账号的唯一权威：注册、登录校验、会员等级、订单与充值都在这里。
 * 云端 agent（{@code mini-agent-app}，prod 档）与客户机本地后端都只是它的调用方。
 *
 * <h3>两条必须守住的边界</h3>
 *
 * <p><b>1. 本服务不签发会话 token。</b>登录成功只回答"这个用户名+密码对应哪个身份"
 * （{@code data.userId} / {@code username} / {@code displayName} / {@code role}）。
 * token 由收到请求的那一侧自己签 —— 客户机本地后端签本地 JWT，云端 agent 签自己的。
 * 这样 HS256 密钥不必在两个服务之间同步，少一处可以配错、且配错会静默降级的地方。
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
}
