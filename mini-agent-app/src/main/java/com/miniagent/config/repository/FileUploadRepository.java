package com.miniagent.config.repository;

import com.miniagent.config.entity.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    List<FileUpload> findByUserId(Long userId);
    List<FileUpload> findBySessionId(String sessionId);
    List<FileUpload> findByUserIdAndSessionId(Long userId, String sessionId);
}
