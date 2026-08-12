package com.miniagent.common;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTextsTest {

    @Test
    void textOnlyHistoryStripsImageUrlParts() {
        UserMessage multi = UserMessage.from(
                TextContent.from("看这张图"),
                ImageContent.from("data:image/png;base64,abc"));
        var out = ChatMessageTexts.textOnlyHistory(List.of(multi));
        assertEquals(1, out.size());
        assertTrue(out.get(0) instanceof UserMessage);
        UserMessage um = (UserMessage) out.get(0);
        assertTrue(um.hasSingleText());
        assertTrue(um.singleText().contains("看这张图"));
        assertTrue(um.singleText().contains("[图片]"));
        assertFalse(um.singleText().contains("base64"));
    }
}
