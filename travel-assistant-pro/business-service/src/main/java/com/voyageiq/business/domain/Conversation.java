package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "conversation")
public class Conversation {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(nullable = false, length = 120)
    private String title;
    @Column(name = "message_count", nullable = false)
    private int messageCount;
    @Column(name = "last_message_preview", length = 240)
    private String lastMessagePreview;
    @Column(name = "last_message_at")
    private Instant lastMessageAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected Conversation() {}

    public static Conversation create(String id, String userId, String tenantId, String title) {
        Conversation value = new Conversation();
        value.id = id;
        value.userId = userId;
        value.tenantId = tenantId;
        value.title = normalizeTitle(title);
        value.createdAt = Instant.now();
        value.updatedAt = value.createdAt;
        return value;
    }

    public void rename(String title) {
        this.title = normalizeTitle(title);
        this.updatedAt = Instant.now();
    }

    public void appendMessage(String preview) {
        this.messageCount += 1;
        this.lastMessagePreview = abbreviate(preview, 220);
        this.lastMessageAt = Instant.now();
        this.updatedAt = this.lastMessageAt;
        if (messageCount == 1 && "新对话".equals(title) && preview != null && !preview.isBlank()) {
            this.title = abbreviate(preview.strip(), 32);
        }
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }

    private static String normalizeTitle(String title) {
        String value = title == null || title.isBlank() ? "新对话" : title.strip();
        return abbreviate(value, 120);
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getTitle() { return title; }
    public int getMessageCount() { return messageCount; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public long getVersion() { return version; }
}

