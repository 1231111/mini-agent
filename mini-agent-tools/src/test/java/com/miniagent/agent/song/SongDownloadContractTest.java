package com.miniagent.agent.song;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.GuardedMediaDownloader;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.config.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SongDownloadContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void blockedProviderAudioUrlIsAnErrorAndNeverAnInlineRemoteLink() throws Exception {
        var service = new SongGenerationService();
        var downloader = mock(GuardedMediaDownloader.class);
        var storage = mock(MediaStorage.class);
        ReflectionTestUtils.setField(service, "mediaDownloader", downloader);
        ReflectionTestUtils.setField(service, "mediaStorage", storage);
        when(downloader.download(anyString(), any(), anyInt()))
                .thenThrow(new GuardedHttpClient.NetworkRejectedException("private target rejected"));

        String result = ReflectionTestUtils.invokeMethod(service, "deliverSong", "task1",
                JSON.readTree("{\"response\":{\"data\":[{\"audio_url\":\"http://127.0.0.1/private\"}]}}"));

        assertThat(JSON.readTree(result).path("errorCode").asText()).isEqualTo(ToolErrorCode.PERMISSION_DENIED.name());
        assertThat(result).doesNotContain("![");
        verifyNoInteractions(storage);
    }

    @Test
    void audioMimeWithHtmlBodyCannotBeDeliveredAsSong() throws Exception {
        var service = new SongGenerationService();
        var downloader = mock(GuardedMediaDownloader.class);
        var storage = mock(MediaStorage.class);
        ReflectionTestUtils.setField(service, "mediaDownloader", downloader);
        ReflectionTestUtils.setField(service, "mediaStorage", storage);
        when(downloader.download(anyString(), any(), anyInt())).thenReturn(
                new GuardedMediaDownloader.Download(200, "https://provider.test/file", "audio/mpeg",
                        "<html>provider failed</html>".getBytes(StandardCharsets.UTF_8)));

        String result = ReflectionTestUtils.invokeMethod(service, "deliverSong", "task2",
                JSON.readTree("{\"response\":{\"data\":[{\"audio_url\":\"https://provider.test/file\"}]}}"));

        assertThat(JSON.readTree(result).path("errorCode").asText()).isEqualTo(ToolErrorCode.EXECUTION_FAILED.name());
        assertThat(result).doesNotContain("![");
        verifyNoInteractions(storage);
    }

    @Test
    void supportedAudioSignatureIsRequiredBeforePersisting() {
        assertThat(GuardedMediaDownloader.isRecognizedAudio("ID3-audio".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(GuardedMediaDownloader.isRecognizedAudio("<html>bad</html>".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
