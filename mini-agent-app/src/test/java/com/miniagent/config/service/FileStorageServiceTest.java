package com.miniagent.config.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    @Test
    void rejectsOversizedBase64BeforeAttemptingDecodeOrDiskAccess() {
        FileStorageService service = new FileStorageService();
        ReflectionTestUtils.setField(service, "maxUploadSizeBytes", 3L);

        assertThatThrownBy(() -> service.saveFile(1L, "session", "file.bin", "application/octet-stream",
                "!!!!!!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file exceeds configured size limit");
    }
}
