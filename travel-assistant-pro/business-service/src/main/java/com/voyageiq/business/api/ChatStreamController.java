package com.voyageiq.business.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.ChatMessage;
import com.voyageiq.business.domain.Conversation;
import com.voyageiq.business.service.AgentGatewayService;
import com.voyageiq.business.service.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/conversations")
public class ChatStreamController {
    private final ConversationService conversations;
    private final AgentGatewayService agentGateway;
    private final ObjectMapper objectMapper;

    public ChatStreamController(ConversationService conversations, AgentGatewayService agentGateway,
                                ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.agentGateway = agentGateway;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/{id}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(Authentication authentication, @PathVariable String id,
                                                @Valid @RequestBody SendMessage request) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String userId = jwt.getSubject();
        Conversation conversation = conversations.requireOwned(id, userId);
        List<ChatMessage> history = conversations.recentHistory(id, userId, 12);
        conversations.append(id, userId, "user", null, request.content(), null, null, null);

        List<Map<String, String>> historyPayload = history.stream()
                .map(message -> Map.of("role", message.getRole(), "content", message.getContent())).toList();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", requestId);
        payload.put("conversation_id", id);
        payload.put("user_id", userId);
        payload.put("tenant_id", conversation.getTenantId());
        payload.put("query", request.content());
        payload.put("history", historyPayload);
        payload.put("state", request.state() == null ? Map.of() : request.state());

        List<Object> cards = new ArrayList<>();
        List<Object> runtime = new ArrayList<>();
        AtomicBoolean persisted = new AtomicBoolean(false);
        return agentGateway.stream(payload)
                .doOnNext(event -> collectCard(event, cards))
                .doOnNext(event -> collectRuntime(event, runtime))
                .doOnNext(event -> persistDoneOnce(event, id, userId, cards, runtime, persisted))
                .onErrorResume(error -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(errorPayload(error))
                        .build()));
    }

    private void collectCard(ServerSentEvent<String> event, List<Object> cards) {
        if (!"card".equals(event.event()) || event.data() == null) return;
        try {
            Map<String, Object> envelope = objectMapper.readValue(event.data(), new TypeReference<>() {});
            Object data = envelope.get("data");
            if (data != null) cards.add(data);
        } catch (Exception ignored) {
            // A malformed optional card must not break the answer stream.
        }
    }

    private void collectRuntime(ServerSentEvent<String> event, List<Object> runtime) {
        if (event.event() == null || event.data() == null
                || List.of("text", "card", "done").contains(event.event())) return;
        try {
            Map<String, Object> envelope = objectMapper.readValue(event.data(), new TypeReference<>() {});
            runtime.add(envelope);
        } catch (Exception ignored) {
            // Runtime details are supplementary and must never interrupt the answer stream.
        }
    }

    private void persistDoneOnce(ServerSentEvent<String> event, String conversationId, String userId,
                                 List<Object> cards, List<Object> runtime, AtomicBoolean persisted) {
        if (!"done".equals(event.event()) || event.data() == null || !persisted.compareAndSet(false, true)) return;
        try {
            Map<String, Object> envelope = objectMapper.readValue(event.data(), new TypeReference<>() {});
            Map<String, Object> data = objectMapper.convertValue(envelope.get("data"), new TypeReference<>() {});
            String answer = String.valueOf(data.getOrDefault("answer", ""));
            String traceId = String.valueOf(envelope.getOrDefault("trace_id", ""));
            String intents = objectMapper.writeValueAsString(data.getOrDefault("intents", List.of()));
            conversations.append(conversationId, userId, "assistant", "response_composer", answer,
                    objectMapper.writeValueAsString(cards), intents, traceId,
                    objectMapper.writeValueAsString(runtime));
        } catch (Exception error) {
            throw new IllegalStateException("Agent result completed but could not be persisted", error);
        }
    }

    private String errorPayload(Throwable error) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "error", "data", Map.of(
                            "code", "AGENT_RUNTIME_UNAVAILABLE",
                            "message", "智能服务暂时不可用，用户消息已保存，可以稍后重试。")));
        } catch (Exception ignored) {
            return "{\"type\":\"error\"}";
        }
    }

    public record SendMessage(@NotBlank @Size(max = 4000) String content,
                              @Size(max = 80) String requestId,
                              Map<String, Object> state) {}
}
