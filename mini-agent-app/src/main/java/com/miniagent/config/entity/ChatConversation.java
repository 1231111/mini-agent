package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_conversations")
public class ChatConversation {
    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 255)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * 会话绑定的工作目录（绝对路径，已规范化）。为空表示跟随默认工作区
     * {@code {dataDir}/workspace}。
     *
     * <p>列宽给到 1024：Windows 长路径 + 中文项目名很容易超过 255，而这条字段一旦
     * 被截断，下次解析就会指向一个不存在的目录，任务会在第一次 write_file 时失败。</p>
     */
    @Column(name = "workspace_root", length = 1024)
    private String workspaceRoot;

    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
