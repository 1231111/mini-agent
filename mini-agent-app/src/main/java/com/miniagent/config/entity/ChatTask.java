package com.miniagent.config.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_tasks")
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
}
