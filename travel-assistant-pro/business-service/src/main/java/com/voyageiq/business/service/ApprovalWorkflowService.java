package com.voyageiq.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.ApprovalAction;
import com.voyageiq.business.domain.ApprovalStatus;
import com.voyageiq.business.domain.TravelApproval;
import com.voyageiq.business.repository.TravelApprovalRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalWorkflowService {
    private final TravelApprovalRepository approvals;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;

    public ApprovalWorkflowService(TravelApprovalRepository approvals, OutboxService outbox,
                                   ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TravelApproval transition(String tenantId, String approvalNo, ApprovalAction action,
                                     String actorRole, long expectedVersion) {
        TravelApproval approval = approvals.findByApprovalNoAndTenantId(approvalNo, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found"));
        if (approval.getVersion() != expectedVersion) {
            throw new IllegalStateException("approval was updated concurrently; refresh before retrying");
        }
        authorize(approval, action, actorRole);
        ApprovalStatus next = approval.transition(action);
        outbox.append(approval.getId(), "Approval" + next.name(), envelope(approval, next));
        return approval;
    }

    private void authorize(TravelApproval approval, ApprovalAction action, String actorRole) {
        ApprovalStatus current = ApprovalStatus.valueOf(approval.getStatus());
        String role = actorRole == null ? "" : actorRole.toUpperCase();
        if (action == ApprovalAction.WITHDRAW || action == ApprovalAction.RESUBMIT) {
            if (!"USER".equals(role)) throw new SecurityException("only requester may withdraw or resubmit");
            return;
        }
        if (current == ApprovalStatus.PENDING_MANAGER && !"MANAGER".equals(role)) {
            throw new SecurityException("manager approval required");
        }
        if (current == ApprovalStatus.PENDING_FINANCE && !"FINANCE".equals(role)) {
            throw new SecurityException("finance approval required");
        }
    }

    private String envelope(TravelApproval approval, ApprovalStatus status) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "approvalId", approval.getId(), "approvalNo", approval.getApprovalNo(),
                    "tenantId", approval.getTenantId(), "eventType", "Approval" + status.name(),
                    "status", status.name()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize approval event", error);
        }
    }
}
