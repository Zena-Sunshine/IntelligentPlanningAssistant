package com.voyageiq.business.service;

import com.voyageiq.business.domain.ChatMessage;
import com.voyageiq.business.domain.Conversation;
import com.voyageiq.business.repository.ChatMessageRepository;
import com.voyageiq.business.repository.ConversationRepository;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional
    public Conversation create(String userId, String tenantId, String title) {
        return conversations.save(Conversation.create(UUID.randomUUID().toString(), userId, tenantId, title));
    }

    @Transactional(readOnly = true)
    public Page<Conversation> list(String userId, String query, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        if (query == null || query.isBlank()) {
            return conversations.findByUserIdAndDeletedAtIsNull(userId, request);
        }
        return conversations.findByUserIdAndDeletedAtIsNullAndTitleContainingIgnoreCase(userId, query.strip(), request);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(String id, String userId) {
        return conversations.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在"));
    }

    @Transactional
    public Conversation rename(String id, String userId, String title) {
        Conversation conversation = requireOwned(id, userId);
        conversation.rename(title);
        return conversation;
    }

    @Transactional
    public void delete(String id, String userId) {
        requireOwned(id, userId).softDelete();
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messageHistory(String id, String userId) {
        requireOwned(id, userId);
        return messages.findByConversationIdOrderByCreatedAtAsc(id);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> recentHistory(String id, String userId, int limit) {
        requireOwned(id, userId);
        List<ChatMessage> values = new java.util.ArrayList<>(
                messages.findByConversationIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit)));
        Collections.reverse(values);
        return values;
    }

    @Transactional
    public ChatMessage append(String conversationId, String userId, String role, String agentKey,
                              String content, String cardsJson, String intents, String traceId) {
        return append(conversationId, userId, role, agentKey, content, cardsJson, intents, traceId, null);
    }

    @Transactional
    public ChatMessage append(String conversationId, String userId, String role, String agentKey,
                              String content, String cardsJson, String intents, String traceId,
                              String runtimeJson) {
        Conversation conversation = requireOwned(conversationId, userId);
        ChatMessage value = messages.save(ChatMessage.of(
                UUID.randomUUID().toString(), conversationId, userId, role, agentKey,
                content, cardsJson, intents, traceId, runtimeJson));
        conversation.appendMessage(content);
        return value;
    }
}
