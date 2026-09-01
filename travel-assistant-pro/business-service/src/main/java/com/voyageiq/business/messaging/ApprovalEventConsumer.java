package com.voyageiq.business.messaging;

import com.voyageiq.business.service.ApprovalEventProjector;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "voyageiq.messaging", name = "enabled", havingValue = "true")
public class ApprovalEventConsumer {
    private final ApprovalEventProjector projector;
    public ApprovalEventConsumer(ApprovalEventProjector projector) { this.projector = projector; }

    @RabbitListener(queues = RabbitMessagingConfig.QUEUE)
    public void consume(Message message) {
        String eventId = message.getMessageProperties().getMessageId();
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("event message id is required");
        projector.process(eventId, new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
