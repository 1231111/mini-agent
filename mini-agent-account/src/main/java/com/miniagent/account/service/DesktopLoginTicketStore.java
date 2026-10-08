package com.miniagent.account.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页注册成功后交给桌面客户端的一次性登录凭证。
 *
 * <p>凭证只活在这一台账号服务的内存里，用过即删。账号服务不签发 JWT，
 * 桌面端拿到身份后再自己签本地会话。
 */
@Component
public class DesktopLoginTicketStore {

    /** 两分钟。够用户从浏览器跳回客户端，过期后必须重新登录。 */
    private static final long TTL_MILLIS = 120_000L;

    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();

    public String issue(long userId) {
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Entry(userId, System.currentTimeMillis() + TTL_MILLIS));
        return ticket;
    }

    public Optional<Long> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Entry entry = tickets.remove(ticket.trim());
        if (entry == null || entry.expiresAtMillis < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.userId);
    }

    private record Entry(long userId, long expiresAtMillis) {
    }
}
