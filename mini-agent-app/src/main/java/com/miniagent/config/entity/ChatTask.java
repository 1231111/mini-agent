package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_tasks", indexes = {
        // 与 V5（MySQL）/ V9（Java 迁移）里的 idx_chat_task_user_deleted_session_created
        // 是同一个索引：列顺序 (user_id, deleted, session_id, created_at) 服务
        // 「某用户未删除的会话列表按时间倒序」这一条查询，不能调换。
        // 实体上必须显式声明：桌面档新装机时 Flyway 先于 Hibernate 跑，那时
        // chat_tasks 还不存在，V9 的守卫会跳过建索引 —— 新装机的索引只能由
        // Hibernate 建表时一并建出。老装机则由 V9 补（Hibernate update 不一定可靠）。
        @Index(name = "idx_chat_task_user_deleted_session_created",
                columnList = "user_id, deleted, session_id, created_at")
})
public class ChatTask extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String question;
    @Column(columnDefinition = "LONGTEXT")
    private String answer;
    /** 用户上传的图片路径（逗号分隔），相对于 conversation-images/ 目录 */
    @Column(name = "images", columnDefinition = "TEXT")
    private String images;
    @Column(nullable = false)
    private boolean deleted = false;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
