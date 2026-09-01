package com.voyageiq.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.TravelApproval;
import com.voyageiq.business.repository.TravelApprovalRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalTransactionService {
    private final TravelApprovalRepository approvals;
    private final PolicyDecisionService decisions;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;

    public ApprovalTransactionService(TravelApprovalRepository approvals, PolicyDecisionService decisions,
                                      OutboxService outbox, ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.decisions = decisions;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TravelApproval createNew(ApprovalSubmission request) {
        PolicyDecisionService.Decision decision = decisions.decide(new PolicyDecisionService.DecisionRequest(
                request.tenantId(), request.travelDate(), request.employeeLevel(), request.cityTier(),
                request.travelType(), request.budget()));
        String number = "VI-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        TravelApproval approval = TravelApproval.create(UUID.randomUUID().toString(), number,
                request.idempotencyKey(), request.userId(), request.tenantId(), request.destination(),
                request.travelDate().toString(), request.budget(), request.reason(), request.employeeLevel(),
                request.cityTier(), request.travelType(), decision.policyVersion(), decision.ruleId(),
                decision.snapshotJson(), decision.explanation(), decision.requiresFinance());
        approvals.saveAndFlush(approval);
        outbox.append(approval.getId(), "ApprovalSubmitted", envelope(approval, "ApprovalSubmitted"));
        return approval;
    }

    private String envelope(TravelApproval approval, String type) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "approvalId", approval.getId(), "approvalNo", approval.getApprovalNo(),
                    "tenantId", approval.getTenantId(), "eventType", type,
                    "status", approval.getStatus()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize approval event", error);
        }
    }

    public record ApprovalSubmission(String idempotencyKey, String userId, String tenantId,
                                     String destination, LocalDate travelDate, BigDecimal budget,
                                     String reason, String employeeLevel, String cityTier,
                                     String travelType) {}
}
