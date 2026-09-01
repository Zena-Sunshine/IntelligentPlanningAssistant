package com.voyageiq.business.service;

import com.voyageiq.business.domain.TravelApproval;
import com.voyageiq.business.repository.TravelApprovalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Objects;

@Service
public class ApprovalCommandService {
    private final TravelApprovalRepository approvals;
    private final ApprovalTransactionService transactions;

    public ApprovalCommandService(TravelApprovalRepository approvals, ApprovalTransactionService transactions) {
        this.approvals = approvals;
        this.transactions = transactions;
    }

    public SubmissionResult submit(ApprovalTransactionService.ApprovalSubmission request) {
        TravelApproval existing = approvals.findByRequestId(request.idempotencyKey()).orElse(null);
        if (existing != null) return new SubmissionResult(validateReplay(existing, request), true);
        try {
            return new SubmissionResult(transactions.createNew(request), false);
        } catch (DataIntegrityViolationException race) {
            TravelApproval winner = approvals.findByRequestId(request.idempotencyKey()).orElseThrow(() -> race);
            return new SubmissionResult(validateReplay(winner, request), true);
        }
    }

    private TravelApproval validateReplay(TravelApproval existing,
                                          ApprovalTransactionService.ApprovalSubmission request) {
        if (!existing.getUserId().equals(request.userId()) || !existing.getTenantId().equals(request.tenantId())) {
            throw new IdempotencyConflictException("idempotency key belongs to another user or tenant");
        }
        if (!existing.getDestination().equals(request.destination())
                || !existing.getTravelDate().equals(request.travelDate().toString())
                || !sameMoney(existing.getBudget(), request.budget())
                || !existing.getReason().equals(request.reason())
                || !existing.getEmployeeLevel().equals(request.employeeLevel())
                || !existing.getCityTier().equals(request.cityTier())
                || !existing.getTravelType().equals(request.travelType())) {
            throw new IdempotencyConflictException("idempotency key was already used with a different request");
        }
        return existing;
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return Objects.equals(left, right);
        return left.compareTo(right) == 0;
    }

    public record SubmissionResult(TravelApproval approval, boolean replayed) {}
}
