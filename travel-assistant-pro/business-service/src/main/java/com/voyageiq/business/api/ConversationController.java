package com.voyageiq.business.api;

import com.voyageiq.business.domain.ChatMessage;
import com.voyageiq.business.domain.Conversation;
import com.voyageiq.business.service.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(Authentication auth, @Valid @RequestBody(required = false) CreateConversation request) {
        Jwt jwt = jwt(auth);
        String title = request == null ? "新对话" : request.title();
        return ConversationView.from(service.create(jwt.getSubject(), jwt.getClaimAsString("tenant_id"), title));
    }

    @GetMapping
    public PageView<ConversationView> list(Authentication auth,
                                           @RequestParam(defaultValue = "") String query,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "30") int size) {
        Page<Conversation> values = service.list(jwt(auth).getSubject(), query, page, size);
        return new PageView<>(values.stream().map(ConversationView::from).toList(),
                values.getNumber(), values.getSize(), values.getTotalElements(), values.getTotalPages());
    }

    @PatchMapping("/{id}")
    public ConversationView rename(Authentication auth, @PathVariable String id,
                                   @Valid @RequestBody RenameConversation request) {
        return ConversationView.from(service.rename(id, jwt(auth).getSubject(), request.title()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable String id) {
        service.delete(id, jwt(auth).getSubject());
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(Authentication auth, @PathVariable String id) {
        return service.messageHistory(id, jwt(auth).getSubject()).stream().map(MessageView::from).toList();
    }

    private static Jwt jwt(Authentication auth) { return (Jwt) auth.getPrincipal(); }

    public record CreateConversation(@Size(max = 120) String title) {}
    public record RenameConversation(@NotBlank @Size(max = 120) String title) {}
    public record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
    public record ConversationView(String id, String title, int messageCount, String lastMessagePreview,
                                   Instant lastMessageAt, Instant createdAt, Instant updatedAt, long version) {
        static ConversationView from(Conversation value) {
            return new ConversationView(value.getId(), value.getTitle(), value.getMessageCount(),
                    value.getLastMessagePreview(), value.getLastMessageAt(), value.getCreatedAt(),
                    value.getUpdatedAt(), value.getVersion());
        }
    }
    public record MessageView(String id, String role, String agentKey, String content, String cardsJson,
                              String intents, String traceId, String runtimeJson, Instant createdAt) {
        static MessageView from(ChatMessage value) {
            return new MessageView(value.getId(), value.getRole(), value.getAgentKey(), value.getContent(),
                    value.getCardsJson(), value.getIntents(), value.getTraceId(), value.getRuntimeJson(),
                    value.getCreatedAt());
        }
    }
}
