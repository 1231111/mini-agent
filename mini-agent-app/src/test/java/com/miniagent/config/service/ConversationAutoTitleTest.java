package com.miniagent.config.service;

import com.miniagent.common.ChatRole;
import com.miniagent.config.entity.ChatConversation;
import com.miniagent.config.entity.ChatMessage;
import com.miniagent.config.repository.ChatConversationRepository;
import com.miniagent.config.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 会话自动标题只认**真实用户消息**，不认系统控制语。
 *
 * <p>控制语在后端也是 {@code role=user} 存库的（它要驱动下一轮对话，这没问题），
 * 但它们不是用户说的话。旧判据是「第几条 user 消息」，
 * 于是首条即控制语时标题就成了那句批注
 * （2026-10-15 实测存在标题为「【系统】已批准「exec_command」，请继续执行。」的会话），
 * 而且后续真实的用户消息因为计数已 &gt;1 **永远补不上标题**。</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationAutoTitleTest {

    private static final String CID = "c-1";

    @Mock
    private ChatConversationRepository conversationRepo;
    @Mock
    private ChatMessageRepository messageRepo;

    @InjectMocks
    private DatabaseConversationStore store;

    private ChatConversation conv;

    @BeforeEach
    void setUp() {
        conv = new ChatConversation();
        conv.setId(CID);
        when(conversationRepo.findById(CID)).thenReturn(Optional.of(conv));
        when(conversationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /**
     * 模拟「本条已落库」后的仓库返回值：最后一条就是刚 addMessage 进来的那条。
     *
     * <p>用 {@code lenient()}：这只是个布置助手，不是每条用例都会走到计数那步 ——
     * assistant 消息在 {@code applyAutoTitle} 的第一道判断就 return 了，本就不该触发它。
     * 严格模式会对这种「布置了但没用上」直接报 UnnecessaryStubbingException，
     * 那不是缺陷信号，是误报。</p>
     */
    private void repoHolds(ChatMessage... prior) {
        lenient().when(messageRepo.findByConversationIdOrderByTimestampAsc(CID))
                .thenReturn(List.of(prior));
    }

    @Test
    void 首条就是控制语时不设标题() {
        String control = "【系统】已批准「exec_command」，请继续执行。";
        repoHolds(msg(ChatRole.USER.getValue(), control));

        store.addMessage(CID, ChatRole.USER.getValue(), control);

        assertNull(conv.getTitle(), "控制语不该成为会话标题");
    }

    @Test
    void 首条是真实用户消息时用它当标题() {
        repoHolds(msg(ChatRole.USER.getValue(), "帮我写一首歌"));

        store.addMessage(CID, ChatRole.USER.getValue(), "帮我写一首歌");

        assertEquals("帮我写一首歌", conv.getTitle());
    }

    /** 这条是本次修复的核心：控制语占位后，真实消息仍必须能把标题补上。 */
    @Test
    void 控制语在前时真实消息仍能补上标题() {
        repoHolds(
                msg(ChatRole.USER.getValue(), "【系统】继续执行已确认的步骤 #1，请继续。"),
                msg(ChatRole.USER.getValue(), "帮我写一首歌"));

        store.addMessage(CID, ChatRole.USER.getValue(), "帮我写一首歌");

        assertEquals("帮我写一首歌", conv.getTitle(),
                "真实消息是第 2 条 user 消息，但它才是第一条真实用户消息");
    }

    /** 已经有真实标题后不再被后续消息改写。 */
    @Test
    void 第二条真实消息不覆盖已有标题() {
        repoHolds(
                msg(ChatRole.USER.getValue(), "帮我写一首歌"),
                msg(ChatRole.USER.getValue(), "再来一首"));

        store.addMessage(CID, ChatRole.USER.getValue(), "再来一首");

        assertNull(conv.getTitle(), "第二条真实消息不该重设标题");
    }

    /** assistant 消息从不参与标题。 */
    @Test
    void 助手消息不设标题() {
        repoHolds(msg(ChatRole.ASSISTANT.getValue(), "好的，我来写。"));

        store.addMessage(CID, ChatRole.ASSISTANT.getValue(), "好的，我来写。");

        assertNull(conv.getTitle());
    }

    /** 超长真实消息截断到 40 字 + 省略号。 */
    @Test
    void 超长首条消息被截断() {
        String longMsg = "一二三四五六七八九十".repeat(5); // 50 字
        repoHolds(msg(ChatRole.USER.getValue(), longMsg));

        store.addMessage(CID, ChatRole.USER.getValue(), longMsg);

        assertEquals(longMsg.substring(0, 40) + "...", conv.getTitle());
    }
}