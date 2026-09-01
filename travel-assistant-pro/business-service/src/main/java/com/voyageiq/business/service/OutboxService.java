package com.voyageiq.business.service;

import com.voyageiq.business.domain.OutboxEvent;
import com.voyageiq.business.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final OutboxEventRepository events;
    public OutboxService(OutboxEventRepository events) { this.events = events; }
    public OutboxEvent append(String approvalId, String eventType, String payload) {
        return events.save(OutboxEvent.create(approvalId, eventType, payload));
    }
}
