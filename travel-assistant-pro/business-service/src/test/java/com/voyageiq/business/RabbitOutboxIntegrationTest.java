package com.voyageiq.business;

import com.voyageiq.business.domain.OutboxStatus;
import com.voyageiq.business.messaging.EventTransport;
import com.voyageiq.business.messaging.RabbitMessagingConfig;
import com.voyageiq.business.repository.ApprovalEventProjectionRepository;
import com.voyageiq.business.repository.OutboxEventRepository;
import com.voyageiq.business.repository.ProcessedMessageRepository;
import com.voyageiq.business.repository.TravelApprovalRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.service.ApprovalCommandService;
import com.voyageiq.business.service.ApprovalTransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "voyageiq.rabbit.integration", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:rabbitintegration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.rabbitmq.host=127.0.0.1",
        "spring.rabbitmq.port=5673",
        "spring.rabbitmq.username=voyageiq",
        "spring.rabbitmq.password=voyageiq_dev",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "voyageiq.messaging.enabled=true",
        "voyageiq.messaging.publish-delay-ms=100",
        "voyageiq.messaging.recovery-delay-ms=1000"
})
class RabbitOutboxIntegrationTest {
    @Autowired ApprovalCommandService commands;
    @Autowired TravelApprovalRepository approvals;
    @Autowired UserAccountRepository users;
    @Autowired OutboxEventRepository outbox;
    @Autowired ProcessedMessageRepository processed;
    @Autowired ApprovalEventProjectionRepository projections;
    @Autowired EventTransport transport;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void clean() {
        rabbitAdmin.purgeQueue(RabbitMessagingConfig.QUEUE, true);
        rabbitAdmin.purgeQueue(RabbitMessagingConfig.DLQ, true);
        projections.deleteAll();
        processed.deleteAll();
        outbox.deleteAll();
        approvals.deleteAll();
    }

    @Test
    void publisherConfirmAndRabbitConsumerCompleteTheOutboxLifecycle() throws Exception {
        var submission = new ApprovalTransactionService.ApprovalSubmission(
                "rabbit-" + UUID.randomUUID(),
                users.findByUsernameIgnoreCase("voyage").orElseThrow().getId(), "tenant-voyage", "上海",
                LocalDate.of(2026, 9, 18), new BigDecimal("1600"), "RabbitMQ 端到端联调",
                "L1", "TIER1", "DOMESTIC");
        var approval = commands.submit(submission).approval();

        await(() -> outbox.countByStatus(OutboxStatus.PUBLISHED.name()) == 1
                && projections.count() == 1, 20_000);
        var event = outbox.findAll().get(0);
        assertEquals(approval.getId(), projections.findAll().get(0).getApprovalId());
        assertEquals(1, processed.count());

        transport.publish(event);
        await(() -> processed.count() == 1 && projections.count() == 1, 5_000);
        assertEquals(1, projections.count(), "broker redelivery must not duplicate the projection side effect");
        assertTrue(event.getPublishedAt() != null || outbox.findById(event.getId()).orElseThrow().getPublishedAt() != null);
    }

    @Test
    void poisonMessageIsRetriedThenDeadLetteredInsteadOfBlockingTheQueue() throws Exception {
        String eventId = "poison-" + UUID.randomUUID();
        var message = MessageBuilder.withBody("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setMessageId(eventId).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send(RabbitMessagingConfig.EXCHANGE, RabbitMessagingConfig.ROUTING_KEY, message);

        await(() -> queueCount(RabbitMessagingConfig.DLQ) == 1, 15_000);
        assertEquals(0, processed.count());
        assertEquals(0, projections.count());
        assertEquals(0, queueCount(RabbitMessagingConfig.QUEUE));
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeoutMillis + "ms");
    }

    private int queueCount(String queue) {
        var properties = rabbitAdmin.getQueueProperties(queue);
        return properties == null ? -1 : (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }
}
