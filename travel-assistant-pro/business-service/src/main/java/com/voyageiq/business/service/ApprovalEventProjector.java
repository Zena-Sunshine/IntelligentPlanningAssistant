package com.voyageiq.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.ApprovalEventProjection;
import com.voyageiq.business.domain.ProcessedMessage;
import com.voyageiq.business.domain.ProcessedMessageId;
import com.voyageiq.business.repository.ApprovalEventProjectionRepository;
import com.voyageiq.business.repository.ProcessedMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalEventProjector {
    public static final String CONSUMER = "approval-projection-v1";
    private final ProcessedMessageRepository processed;
    private final ApprovalEventProjectionRepository projections;
    private final ObjectMapper objectMapper;

    public ApprovalEventProjector(ProcessedMessageRepository processed,
                                  ApprovalEventProjectionRepository projections, ObjectMapper objectMapper) {
        this.processed = processed;
        this.projections = projections;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean process(String eventId, String payload) {
        ProcessedMessageId key = new ProcessedMessageId(eventId, CONSUMER);
        if (processed.existsById(key)) return false;
        try {
            JsonNode json = objectMapper.readTree(payload);
            processed.save(new ProcessedMessage(eventId, CONSUMER));
            projections.save(new ApprovalEventProjection(eventId, json.path("approvalId").asText(),
                    json.path("eventType").asText()));
            return true;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid approval event payload", error);
        }
    }
}
