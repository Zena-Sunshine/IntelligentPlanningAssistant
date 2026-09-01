package com.voyageiq.business;

import com.voyageiq.business.domain.OutboxEvent;
import com.voyageiq.business.domain.OutboxStatus;
import com.voyageiq.business.repository.ApprovalEventProjectionRepository;
import com.voyageiq.business.repository.OutboxEventRepository;
import com.voyageiq.business.repository.ProcessedMessageRepository;
import com.voyageiq.business.service.ApprovalEventProjector;
import com.voyageiq.business.service.OutboxService;
import com.voyageiq.business.service.OutboxTransactionService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "voyageiq.messaging.enabled=false"
})
class OutboxReliabilityIntegrationTest {
    @Autowired OutboxService outbox;
    @Autowired OutboxTransactionService transactions;
    @Autowired OutboxEventRepository events;
    @Autowired ApprovalEventProjector projector;
    @Autowired ProcessedMessageRepository processed;
    @Autowired ApprovalEventProjectionRepository projections;

    @BeforeEach
    void clean() {
        projections.deleteAll();
        processed.deleteAll();
        events.deleteAll();
    }

    @Test
    void claimAndPublisherConfirmAdvanceEventToPublished() {
        OutboxEvent event = outbox.append(UUID.randomUUID().toString(), "ApprovalSubmitted", payload());
        OutboxEvent claimed = transactions.claim(10).stream()
                .filter(item -> item.getId().equals(event.getId())).findFirst().orElseThrow();
        assertEquals(OutboxStatus.PROCESSING.name(), claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        transactions.markPublished(event.getId());
        OutboxEvent published = events.findById(event.getId()).orElseThrow();
        assertEquals(OutboxStatus.PUBLISHED.name(), published.getStatus());
        assertNotNull(published.getPublishedAt());
    }

    @Test
    void publishFailureReturnsEventToPendingWithBackoff() {
        OutboxEvent event = outbox.append(UUID.randomUUID().toString(), "ApprovalSubmitted", payload());
        transactions.claim(100);
        transactions.markFailed(event.getId(), "broker unavailable");
        OutboxEvent failed = events.findById(event.getId()).orElseThrow();
        assertEquals(OutboxStatus.PENDING.name(), failed.getStatus());
        assertEquals("broker unavailable", failed.getLastError());
        assertTrue(transactions.claim(100).stream().noneMatch(item -> item.getId().equals(event.getId())));
    }

    @Test
    void duplicateDeliveriesCreateOneProjectionAndOneProcessedMarker() {
        String eventId = UUID.randomUUID().toString();
        assertTrue(projector.process(eventId, payload()));
        for (int i = 0; i < 1000; i++) assertFalse(projector.process(eventId, payload()));
        assertEquals(1, processed.count());
        assertEquals(1, projections.count());
    }

    @Test
    void invalidPayloadRollsBackDedupeMarker() {
        String eventId = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> projector.process(eventId, "not-json"));
        assertEquals(0, processed.count());
        assertEquals(0, projections.count());
    }

    @Test
    void publishedEventIsNeverClaimedAgain() {
        OutboxEvent event = outbox.append(UUID.randomUUID().toString(), "ApprovalSubmitted", payload());
        transactions.claim(100);
        transactions.markPublished(event.getId());
        assertTrue(transactions.claim(100).stream().noneMatch(item -> item.getId().equals(event.getId())));
    }

    @Test
    void recoveryWithNoStaleProcessingEventsIsSafe() {
        assertEquals(0, transactions.recoverStale(Duration.ofSeconds(30), 100));
    }

    @Test
    void crashedPublisherClaimIsRecoveredToPending() {
        OutboxEvent event = outbox.append(UUID.randomUUID().toString(), "ApprovalSubmitted", payload());
        transactions.claim(100);
        assertEquals(1, transactions.recoverStale(Duration.ZERO, 100));
        OutboxEvent recovered = events.findById(event.getId()).orElseThrow();
        assertEquals(OutboxStatus.PENDING.name(), recovered.getStatus());
        assertEquals("recovered stale processing event", recovered.getLastError());
        assertTrue(transactions.claim(100).stream().anyMatch(item -> item.getId().equals(event.getId())));
    }

    private String payload() {
        return "{\"approvalId\":\"approval-1\",\"eventType\":\"ApprovalSubmitted\"}";
    }
}
