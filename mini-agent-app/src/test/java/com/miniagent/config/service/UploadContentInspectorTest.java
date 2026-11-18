package com.miniagent.config.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UploadContentInspectorTest {
    @Test
    void rejectsUnknownBinaryInsteadOfTrustingOctetStream() {
        var result = UploadContentInspector.inspect("payload.bin", "application/octet-stream",
                new byte[]{0, 1, 2, 3, 4, 5});

        assertThat(result.accepted()).isFalse();
        assertThat(result.detected()).isEqualTo("unknown");
    }

    @Test
    void recognizesPdfByMagicBytesAndRejectsSpoofedTextMime() {
        byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(UploadContentInspector.inspect("report.pdf", "application/pdf", pdf).accepted())
                .isTrue();
        assertThat(UploadContentInspector.inspect("report.pdf", "text/plain", pdf).accepted())
                .isFalse();
    }

    @Test
    void recognizesOfficeZipOnlyWithSupportedExtension() {
        byte[] zip = new byte[]{'P', 'K', 3, 4, 1, 2};
        assertThat(UploadContentInspector.inspect("report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip).accepted())
                .isTrue();
        assertThat(UploadContentInspector.inspect("payload.bin", "application/octet-stream", zip).accepted())
                .isFalse();
    }

    @Test
    void acceptsUtf8TextAndChecksBinaryMimeMismatch() {
        byte[] text = "标题\ncontent".getBytes(StandardCharsets.UTF_8);
        assertThat(UploadContentInspector.inspect("note.txt", "text/plain", text).accepted())
                .isTrue();
        assertThat(UploadContentInspector.inspect("note.txt", "image/png", text).accepted())
                .isFalse();
    }
}
