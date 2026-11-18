package com.miniagent.config.service;

import com.miniagent.agent.web.UploadedDocumentService;
import com.miniagent.config.entity.FileUpload;
import com.miniagent.config.repository.FileUploadRepository;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsOversizedBase64BeforeAttemptingDecodeOrDiskAccess() {
        FileStorageService service = new FileStorageService();
        ReflectionTestUtils.setField(service, "maxUploadSizeBytes", 3L);

        assertThatThrownBy(() -> service.saveFile(1L, "session", "file.bin", "application/octet-stream",
                "!!!!!!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file exceeds configured size limit");
    }

    @Test
    void byteArrayUploadSkipsExtractionAboveTheSameLimitAsMultipartUpload() {
        FileStorageService service = new FileStorageService();
        FileUploadRepository uploads = mock(FileUploadRepository.class);
        UploadedDocumentService documents = mock(UploadedDocumentService.class);
        ReflectionTestUtils.setField(service, "fileUploadRepository", uploads);
        ReflectionTestUtils.setField(service, "uploadedDocumentService", documents);
        ReflectionTestUtils.setField(service, "baseDir", tempDir);
        ReflectionTestUtils.setField(service, "maxUploadSizeBytes", 1024L);
        ReflectionTestUtils.setField(service, "maxExtractBytes", 4L);

        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.UTF_8);
        service.saveUploaded(1L, "session", "large.pdf", "application/pdf",
                new MockMultipartFile("file", "large.pdf", "application/pdf", pdf));
        service.saveFile(1L, "session", "large.pdf", "application/pdf", pdf);

        verify(uploads, times(2)).save(any(FileUpload.class));
        verify(documents, never()).extractAndWriteSidecar(anyLong(), any(Path.class), anyString(), anyString());
    }
}
