package com.voyageiq.business.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "voyageiq.messaging", name = "enabled", havingValue = "true")
public class RabbitMessagingConfig {
    public static final String EXCHANGE = "voyageiq.business.events";
    public static final String QUEUE = "voyageiq.approval.projection";
    public static final String DLX = "voyageiq.business.events.dlx";
    public static final String DLQ = "voyageiq.approval.projection.dlq";
    public static final String ROUTING_KEY = "approval.event";

    @Bean DirectExchange businessExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }
    @Bean Queue approvalQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(ROUTING_KEY).build();
    }
    @Bean Queue approvalDeadLetterQueue() { return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding approvalBinding(Queue approvalQueue, DirectExchange businessExchange) {
        return BindingBuilder.bind(approvalQueue).to(businessExchange).with(ROUTING_KEY);
    }
    @Bean Binding approvalDlqBinding(Queue approvalDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(approvalDeadLetterQueue).to(deadLetterExchange).with(ROUTING_KEY);
    }
}
