package com.miniagent.agent.security;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Bounded provider-media download that applies SSRF policy to every redirect hop. */
@Component
public class GuardedMediaDownloader {
    private final GuardedHttpClient http;

    public GuardedMediaDownloader(GuardedHttpClient http) {
        this.http = http;
    }

    public Download download(String url, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        GuardedHttpClient.Response response = http.get(url,
                Map.of("User-Agent", "MiniAgent/1.0", "Accept", "*/*"), timeout, maxBytes);
        return new Download(response.statusCode(), response.finalUri().toString(),
                response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    /** Supported generated-audio container signatures; do not trust an audio/* MIME alone. */
    public static boolean isRecognizedAudio(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        boolean mp3 = bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3'
                || (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0;
        boolean ogg = bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
        boolean flac = bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C';
        boolean wav = bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
        boolean mp4 = bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
        return mp3 || ogg || flac || wav || mp4;
    }

    public record Download(int statusCode, String finalUrl, String contentType, byte[] body) {
        public Download {
            finalUrl = finalUrl == null ? "" : finalUrl;
            contentType = contentType == null ? "" : contentType;
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() { return body.clone(); }
    }
}
