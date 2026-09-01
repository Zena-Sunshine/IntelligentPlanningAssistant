package com.voyageiq.business.service;

import com.voyageiq.business.domain.OutboxEvent;
import com.voyageiq.business.domain.OutboxStatus;
import com.voyageiq.business.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxTransactionService {
    private final OutboxEventRepository events;
    public OutboxTransactionService(OutboxEventRepository events) { this.events = events; }

    @Transactional
    public List<OutboxEvent> claim(int batchSize) {
        List<OutboxEvent> claimed = events.claimable(OutboxStatus.PENDING.name(), Instant.now(),
                PageRequest.of(0, Math.max(1, Math.min(batchSize, 500))));
        claimed.forEach(OutboxEvent::claim);
        return events.saveAll(claimed);
    }

    @Transactional
    public void markPublished(String id) {
        OutboxEvent event = events.findById(id).orElseThrow();
        event.markPublished();
    }

    @Transactional
    public void markFailed(String id, String error) {
        OutboxEvent event = events.findById(id).orElseThrow();
        event.markFailed(error);
    }

    @Transactional
    public int recoverStale(Duration timeout, int limit) {
        List<OutboxEvent> stale = events.findByStatusAndProcessingStartedAtLessThanOrderByCreatedAtAsc(
                OutboxStatus.PROCESSING.name(), Instant.now().minus(timeout), PageRequest.of(0, limit));
        stale.forEach(OutboxEvent::recover);
        return stale.size();
    }
}
