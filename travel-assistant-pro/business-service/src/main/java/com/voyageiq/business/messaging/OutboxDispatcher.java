package com.voyageiq.business.messaging;

import com.voyageiq.business.config.VoyageIqProperties;
import com.voyageiq.business.domain.OutboxEvent;
import com.voyageiq.business.service.OutboxTransactionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "voyageiq.messaging", name = "enabled", havingValue = "true")
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxTransactionService transactions;
    private final EventTransport transport;
    private final VoyageIqProperties properties;

    public OutboxDispatcher(OutboxTransactionService transactions, EventTransport transport,
                            VoyageIqProperties properties) {
        this.transactions = transactions;
        this.transport = transport;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${voyageiq.messaging.publish-delay-ms:1000}")
    public void publishPending() {
        List<OutboxEvent> claimed = transactions.claim(properties.messaging().publishBatchSize());
        for (OutboxEvent event : claimed) {
            try {
                transport.publish(event);
                transactions.markPublished(event.getId());
            } catch (Exception error) {
                log.warn("outbox publish failed event={} attempt={}: {}", event.getId(),
                        event.getAttemptCount(), error.getMessage());
                transactions.markFailed(event.getId(), error.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${voyageiq.messaging.recovery-delay-ms:10000}")
    public void recoverStale() {
        int recovered = transactions.recoverStale(properties.messaging().processingTimeout(), 500);
        if (recovered > 0) log.warn("recovered {} stale outbox events", recovered);
    }
}
