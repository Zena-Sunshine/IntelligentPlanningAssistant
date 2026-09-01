package com.voyageiq.business.messaging;

import com.voyageiq.business.domain.OutboxEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "voyageiq.messaging", name = "enabled", havingValue = "true")
public class RabbitEventTransport implements EventTransport {
    private final RabbitTemplate rabbit;
    public RabbitEventTransport(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        CorrelationData correlation = new CorrelationData(event.getId());
        rabbit.convertAndSend(RabbitMessagingConfig.EXCHANGE, RabbitMessagingConfig.ROUTING_KEY,
                event.getPayload(), message -> {
                    message.getMessageProperties().setMessageId(event.getId());
                    message.getMessageProperties().setHeader("eventType", event.getEventType());
                    message.getMessageProperties().setHeader("aggregateId", event.getAggregateId());
                    return message;
                }, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("rabbit publisher confirm rejected: " + confirm.getReason());
    }
}
