package com.miniagent.config.cloud;

import com.miniagent.config.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 云端账号的编排入口：先问云端「你是谁」，再把它对应到本地的一行用户。
 *
 * <p>刻意<b>不加</b> {@code @Transactional}。这里做的事是"网络 + 落库"，
 * 而网络调用必须留在事务之外：把云端往返（最长 10 秒）包进事务，
 * 会让一个慢请求长时间占着数据库连接和行锁，整个应用的写路径跟着一起卡。
 * 落库那半边在 {@link ShadowUserService} 里单独开事务。
 *
 * <p>配置为空（{@code agent.auth.cloud.base-url} 未设）时 {@link #enabled()} 为 false，
 * 调用方应退回本地账号流程 —— 这样开发态和单机测试不必为了登录一次去起第二份实例。
 *
 * <h3>两种角色，一个入口</h3>
 *
 * <p>{@code base-url} 指向的是<b>账号服务</b>（{@code mini-agent-account}），
 * 它在两个场景下的含义不同，而区别只由 {@code agent.auth.cloud.shared-database} 表达：
 *
 * <ul>
 *   <li><b>客户机本地后端</b>（{@code shared-database=false}）：
 *       本机库与账号库是两个库，登录成功后造一行影子用户当锚点，
 *       本地鉴权链（{@code SignedSessionFilter} 每请求都要 findById）才有落点。</li>
 *   <li><b>云端 agent</b>（{@code shared-database=true}）：
 *       与账号服务同库，账号行本身就是本机行，不能再造第二行 —— 否则会员配额会写在
 *       账号那个租户上，而 agent 读的是影子行的租户。详见 {@link ShadowUserService}。</li>
 * </ul>
 */
@Service
public class CloudAccountService {

    @Autowired
    private CloudAccountClient client;
    @Autowired
    private ShadowUserService shadowUsers;

    public boolean enabled() {
        return client.configured();
    }

    /**
     * 云端账号状态。给界面在"用户还没输账号"时用：
     * 「必须联网才能登录」这件事应该提前说清楚，而不是让用户输完密码再报网络错。
     *
     * @param enabled   是否配置了云端账号服务
     * @param reachable 云端是否可达且健康
     * @param error     不可达时的可读原因；可达时为 null
     */
    public record CloudStatus(boolean enabled, boolean reachable, String error) {
    }

    public CloudStatus status() {
        if (!client.configured()) {
            return new CloudStatus(false, false, "未配置云端账号服务");
        }
        try {
            client.health();
            return new CloudStatus(true, true, null);
        } catch (CloudAccountException e) {
            // 这里不回传 baseUrl：本服务的监听地址是 0.0.0.0，局域网内也能访问，
            // 而云端地址属于部署细节，不该从一个未认证接口漏出去。
            return new CloudStatus(true, false, e.getMessage());
        }
    }

    /** 登录：云端校验账号密码，成功后返回本地影子用户。 */
    public User login(String username, String password) {
        CloudUser cloudUser = client.login(username, password);
        return shadowUsers.materialize(cloudUser);
    }

    /** 注册：账号落在云端，本地只建影子用户。 */
    public User register(String username, String password, String displayName) {
        CloudUser cloudUser = client.register(username, password, displayName);
        return shadowUsers.materialize(cloudUser);
    }
}
