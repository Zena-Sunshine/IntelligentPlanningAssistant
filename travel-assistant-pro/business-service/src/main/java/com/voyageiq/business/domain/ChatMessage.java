package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessage {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, length = 20)
    private String role;
    @Column(name = "agent_key", length = 64)
    private String agentKey;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(name = "cards_json", columnDefinition = "TEXT")
    private String cardsJson;
    @Column(length = 255)
    private String intents;
    @Column(name = "trace_id", length = 64)
    private String traceId;
    @Column(name = "runtime_json", columnDefinition = "TEXT")
    private String runtimeJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() {}

    public static ChatMessage of(String id, String conversationId, String userId, String role,
                                 String agentKey, String content, String cardsJson,
                                 String intents, String traceId, String runtimeJson) {
        ChatMessage value = new ChatMessage();
        value.id = id;
        value.conversationId = conversationId;
        value.userId = userId;
        value.role = role;
        value.agentKey = agentKey;
        value.content = content;
        value.cardsJson = cardsJson;
        value.intents = intents;
        value.traceId = traceId;
        value.runtimeJson = runtimeJson;
        value.createdAt = Instant.now();
        return value;
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getUserId() { return userId; }
    public String getRole() { return role; }
    public String getAgentKey() { return agentKey; }
    public String getContent() { return content; }
    public String getCardsJson() { return cardsJson; }
    public String getIntents() { return intents; }
    public String getTraceId() { return traceId; }
    public String getRuntimeJson() { return runtimeJson; }
    public Instant getCreatedAt() { return createdAt; }
}
