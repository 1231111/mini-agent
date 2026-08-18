package com.miniagent.config.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "agent_user_memory")
public class AgentUserMemory extends BaseEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Lob
    @Column(name = "memory_content", columnDefinition = "LONGTEXT")
    private String memoryContent = "";

    @Lob
    @Column(name = "user_content", columnDefinition = "LONGTEXT")
    private String userContent = "";

    @Lob
    @Column(name = "midterm_content", columnDefinition = "LONGTEXT")
    private String midtermContent = "";

    @Version
    @Column(name = "version")
    private Long version;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getMemoryContent() { return memoryContent; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public String getUserContent() { return userContent; }
    public void setUserContent(String userContent) { this.userContent = userContent; }
    public String getMidtermContent() { return midtermContent; }
    public void setMidtermContent(String midtermContent) { this.midtermContent = midtermContent; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
