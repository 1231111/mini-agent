package com.miniagent.agent.web;

import com.miniagent.agent.security.GuardedMediaDownloader;
import com.miniagent.config.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImagePersistenceContractTest {
    @TempDir Path imageDir;

    @Test
    void fakeImageMimeCannotMakeHtmlErrorPageBecomeImage() throws Exception {
        ImageGenerationService service = new ImageGenerationService();
        GuardedMediaDownloader downloader = mock(GuardedMediaDownloader.class);
        MediaStorage storage = mock(MediaStorage.class);
        ReflectionTestUtils.setField(service, "mediaDownloader", downloader);
        ReflectionTestUtils.setField(service, "mediaStorage", storage);
        when(downloader.download(anyString(), any(), anyInt())).thenReturn(
                new GuardedMediaDownloader.Download(200, "https://provider.test/image", "image/png",
                        "<html>gateway failure</html>".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.persistImage("https://provider.test/image", null)).isNull();
        verifyNoInteractions(storage);
        assertThat(ImageGenerationService.imageExtension("oops".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isNull();
    }

    @Test
    void downloadFailureDoesNotReturnUnverifiedRemoteUrlAsSuccess() throws Exception {
        ImageGenerationService service = new ImageGenerationService();
        GuardedMediaDownloader downloader = mock(GuardedMediaDownloader.class);
        ReflectionTestUtils.setField(service, "mediaDownloader", downloader);
        when(downloader.download(anyString(), any(), anyInt())).thenThrow(new java.io.IOException("timeout"));

        assertThat(service.persistImage("https://provider.test/image", null)).isNull();
    }

    @Test
    void dataUriMustHaveRecognizedBytesAndOnlyKnownPersistedRoutesAreAccepted() {
        ImageGenerationService service = new ImageGenerationService();
        assertThat(service.persistImage("data:image/png;base64,b29wcw==", null)).isNull();
        assertThat(service.persistImage("javascript:alert(1)", null)).isNull();
        assertThat(service.persistImage("/api/generated-media/1/image.png", null))
                .isEqualTo("/api/generated-media/1/image.png");
    }

    @Test
    void failedApiCallNeverReusesAnUnrelatedRecentImage() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", exchange -> {
            byte[] error = "{\"error\":{\"message\":\"provider failed\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            try (var output = exchange.getResponseBody()) { output.write(error); }
        });
        server.start();
        try {
            Files.write(imageDir.resolve("unrelated.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            var storage = mock(MediaStorage.class);
            when(storage.generatedOwnerDir()).thenReturn(imageDir);
            var service = new ImageGenerationService();
            ReflectionTestUtils.setField(service, "mediaStorage", storage);
            ReflectionTestUtils.setField(service, "chatAnywhereBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "chatAnywhereKey", "test-key");
            ReflectionTestUtils.setField(service, "chatAnywhereModel", "test-model");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                    service, "generateImageBy2ca", "test", "square_hd"))
                    .hasMessageContaining("provider failed");
            verify(storage, never()).generatedUrl(anyString());
            try (var files = Files.list(imageDir)) {
                assertThat(files.count()).isEqualTo(1);
            }
        } finally { server.stop(0); }
    }
}
