package com.miniagent.config.security;

import java.time.Duration;

/**
 * 服务端会话记录的存储后端。
 *
 * <p>为什么需要这一层：JWT 的 {@code exp} 只是硬上限（{@code agent.auth.jwt-exp-seconds}，
 * 默认 7 天），它一个人撑不起会话时长语义。真正决定「这个会话还算不算数」的是服务端
 * 另外存的一份记录，它负责两件事：
 * <ul>
 *   <li><b>空闲超时</b> —— {@code agent.auth.jwt-ttl-seconds}（默认 30 分钟）内没有请求
 *       就失效，不等 JWT 自然过期。</li>
 *   <li><b>登出立即吊销</b> —— 用户点退出，那个 token 立刻作废。</li>
 * </ul>
 * 这份记录原先只放在 Redis（键 {@code session:jwt:{jti}}）。桌面客户端
 * （{@code --spring.profiles.active=desktop}）不提供 Redis，所以需要第二种落点。
 *
 * <p>后端按 {@code agent.replica.mode} 选择，口径与本项目其余 Redis 相关组件
 * （{@code RedisReplicaConfig} / {@code RedisRateLimiter} / {@code RedisTaskConcurrency}）一致：
 * <ul>
 *   <li>{@code redis} —— {@link RedisSessionStore}，滑动超时交给 Redis 的键 TTL。</li>
 *   <li>{@code local}（默认档与 desktop 档）—— {@link DbSessionStore}，
 *       落在 {@code auth_sessions} 表。</li>
 * </ul>
 *
 * <p>方法参数收的是 <b>token 原文</b>而不是 jti：键由 {@link SessionKeys#digest} 统一派生，
 * 所以这层不需要知道 JWT 的内部结构，两个后端也不会各自发明一套键规则。
 *
 * <p><b>不要用「redis 这个 bean 是不是 null」来判断 Redis 能不能用。</b>
 * {@code RedisAutoConfiguration} 只判断 redis 相关 class 在不在 classpath 上，
 * 不看服务端是否可达。没有 Redis 服务端时照样会注入一个非 null 的
 * {@code StringRedisTemplate}，然后在第一次调用时抛 {@code RedisConnectionFailureException}。
 * 本接口的存在就是为了不再依赖那个判断。
 *
 * <p><b>注意本接口的实现类不能用 {@code @ConditionalOnBean} 选后端。</b>
 * Spring Boot 的自动配置是通过 {@code DeferredImportSelector} 在用户配置<b>之后</b>
 * 才处理的，所以在普通 {@code @Component} 上判 {@code @ConditionalOnBean(StringRedisTemplate.class)}
 * 永远判不中、而 {@code @ConditionalOnMissingBean} 永远判得中 —— 结果是 Redis 模式
 * 下也悄悄用了数据库后端。条件必须落在 Environment 属性上（即 {@code agent.replica.mode}）。
 */
public interface SessionStore {

    /**
     * 记录一次登录。
     *
     * @param token 刚签发给客户端的 JWT 原文（本方法内部只取它的摘要）
     * @param userId 该会话属于谁
     * @param ttl   空闲超时窗口
     */
    void create(String token, Long userId, Duration ttl);

    /**
     * 校验会话并做滑动续期 —— 每个带鉴权的请求都会走一次。
     *
     * @return 有效的 userId；不存在 / 已吊销 / 已空闲超时一律返回 {@code null}
     */
    Long validateAndTouch(String token, Duration ttl);

    /** 登出：立即吊销该会话。 */
    void revoke(String token);

    /** 后端标识，用于启动日志 —— 排查客户机上「会话怎么突然失效了」时第一眼要看的东西。 */
    String backendName();
}
