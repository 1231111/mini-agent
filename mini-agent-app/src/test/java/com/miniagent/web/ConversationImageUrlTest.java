package com.miniagent.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationImageUrlTest {

    @Test
    void mapsRelativeKeysToHttpPaths() {
        var urls = MiniAgentChatPageController.toConversationImageUrls(
                "conversation-images/s1/a.png,s1/b.jpg");
        assertEquals(List.of(
                "/conversation-images/s1/a.png",
                "/conversation-images/s1/b.jpg"), urls);
    }

    @Test
    void blankYieldsEmpty() {
        assertTrue(MiniAgentChatPageController.toConversationImageUrls(null).isEmpty());
        assertTrue(MiniAgentChatPageController.toConversationImageUrls("  ").isEmpty());
    }
}
