package com.miniagent.agent.song;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.LyriaMusicParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyriaMusicServiceTest {

    @Test
    void 从steps取出音频() throws Exception {
        String json = """
                {"steps":[
                  {"type":"model_output","content":[
                    {"type":"text","text":"lyrics"},
                    {"type":"audio","mime_type":"audio/mpeg","data":"QQ=="}
                  ]}
                ]}
                """;
        LyriaMusicService.AudioHit hit = LyriaMusicService.extract(new ObjectMapper().readTree(json));
        assertEquals("QQ==", hit.base64());
        assertEquals("audio/mpeg", hit.mime());
    }

    @Test
    void 包在interaction里也能取出() throws Exception {
        String json = """
                {"interaction":{"output_audio":{"data":"QQ==","mime_type":"audio/wav"}}}
                """;
        LyriaMusicService.AudioHit hit = LyriaMusicService.extract(new ObjectMapper().readTree(json));
        assertEquals("QQ==", hit.base64());
        assertEquals("audio/wav", hit.mime());
    }

    @Test
    void prompt拼上歌词和纯音乐标记() {
        LyriaMusicParams p = new LyriaMusicParams();
        p.setPrompt("pop");
        p.setLyrics("[Chorus]\nhi");
        p.setInstrumental(true);
        String input = LyriaMusicService.buildInput(p);
        assertTrue(input.startsWith("Instrumental only"));
        assertTrue(input.contains("Sing these lyrics"));
        assertTrue(input.contains("[Chorus]"));
    }
}
