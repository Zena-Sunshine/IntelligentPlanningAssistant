package com.voyageiq.business;

import com.voyageiq.business.domain.ApprovalAction;
import com.voyageiq.business.domain.TravelApproval;
import com.voyageiq.business.domain.UserAccount;
import com.voyageiq.business.repository.OutboxEventRepository;
import com.voyageiq.business.repository.TravelApprovalRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.service.ApprovalCommandService;
import com.voyageiq.business.service.ApprovalTransactionService;
import com.voyageiq.business.service.ApprovalWorkflowService;
import com.voyageiq.business.service.IdempotencyConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "voyageiq.messaging.enabled=false"
})
class ApprovalWorkflowIntegrationTest {
    @Autowired ApprovalCommandService commands;
    @Autowired ApprovalWorkflowService workflow;
    @Autowired TravelApprovalRepository approvals;
    @Autowired OutboxEventRepository outbox;
    @Autowired UserAccountRepository users;

    @Test
    void submissionPersistsPolicySnapshotAndOutboxInOneTransaction() {
        var result = commands.submit(request("snapshot", "1800"));
        TravelApproval value = result.approval();
        assertFalse(result.replayed());
        assertEquals(1, value.getPolicyVersion());
        assertNotNull(value.getPolicyRuleId());
        assertTrue(value.getPolicySnapshot().contains("MANAGER_THEN_FINANCE"));
        assertTrue(value.isRequiresFinance());
        assertEquals(1, outbox.countByAggregateId(value.getId()));
    }

    @Test
    void identicalIdempotencyKeyReturnsOriginalWithoutSecondOutboxEvent() {
        var request = request("replay", "900");
        var first = commands.submit(request);
        var second = commands.submit(request);
        assertEquals(first.approval().getId(), second.approval().getId());
        assertTrue(second.replayed());
        assertEquals(1, outbox.countByAggregateId(first.approval().getId()));
    }

    @Test
    void reusingIdempotencyKeyWithDifferentPayloadIsRejected() {
        var first = request("payload-conflict", "900");
        commands.submit(first);
        var changed = new ApprovalTransactionService.ApprovalSubmission(first.idempotencyKey(), first.userId(),
                first.tenantId(), "北京", first.travelDate(), first.budget(), first.reason(),
                first.employeeLevel(), first.cityTier(), first.travelType());
        assertThrows(IdempotencyConflictException.class, () -> commands.submit(changed));
    }

    @Test
    void reusingIdempotencyKeyWithChangedBudgetOrPolicyDimensionsIsRejected() {
        var first = request("policy-payload-conflict", "900");
        commands.submit(first);
        var changedBudget = new ApprovalTransactionService.ApprovalSubmission(first.idempotencyKey(),
                first.userId(), first.tenantId(), first.destination(), first.travelDate(),
                new BigDecimal("901"), first.reason(), first.employeeLevel(), first.cityTier(),
                first.travelType());
        var changedTier = new ApprovalTransactionService.ApprovalSubmission(first.idempotencyKey(),
                first.userId(), first.tenantId(), first.destination(), first.travelDate(), first.budget(),
                first.reason(), first.employeeLevel(), "TIER1", first.travelType());
        assertThrows(IdempotencyConflictException.class, () -> commands.submit(changedBudget));
        assertThrows(IdempotencyConflictException.class, () -> commands.submit(changedTier));
    }

    @Test
    void overBudgetApprovalRequiresManagerThenFinance() {
        TravelApproval created = commands.submit(request("finance-route", "1800")).approval();
        TravelApproval manager = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.APPROVE, "MANAGER", created.getVersion());
        assertEquals("PENDING_FINANCE", manager.getStatus());
        TravelApproval finance = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.APPROVE, "FINANCE", manager.getVersion());
        assertEquals("APPROVED", finance.getStatus());
        assertEquals(3, outbox.countByAggregateId(created.getId()));
    }

    @Test
    void withinPolicyApprovalSkipsFinance() {
        TravelApproval created = commands.submit(request("manager-only", "900")).approval();
        TravelApproval approved = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.APPROVE, "MANAGER", created.getVersion());
        assertEquals("APPROVED", approved.getStatus());
    }

    @Test
    void wrongRoleAndIllegalTransitionAreRejected() {
        TravelApproval created = commands.submit(request("role-check", "1800")).approval();
        assertThrows(SecurityException.class, () -> workflow.transition(created.getTenantId(),
                created.getApprovalNo(), ApprovalAction.APPROVE, "FINANCE", created.getVersion()));
        TravelApproval manager = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.APPROVE, "MANAGER", created.getVersion());
        TravelApproval approved = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.APPROVE, "FINANCE", manager.getVersion());
        assertThrows(IllegalStateException.class, () -> workflow.transition(approved.getTenantId(),
                approved.getApprovalNo(), ApprovalAction.APPROVE, "FINANCE", approved.getVersion()));
    }

    @Test
    void staleOptimisticVersionIsRejected() {
        TravelApproval created = commands.submit(request("stale", "900")).approval();
        workflow.transition(created.getTenantId(), created.getApprovalNo(), ApprovalAction.APPROVE,
                "MANAGER", created.getVersion());
        assertThrows(IllegalStateException.class, () -> workflow.transition(created.getTenantId(),
                created.getApprovalNo(), ApprovalAction.WITHDRAW, "USER", created.getVersion()));
    }

    @Test
    void withdrawnRequestCanBeResubmitted() {
        TravelApproval created = commands.submit(request("withdraw", "900")).approval();
        TravelApproval withdrawn = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.WITHDRAW, "USER", created.getVersion());
        assertEquals("WITHDRAWN", withdrawn.getStatus());
        TravelApproval resubmitted = workflow.transition(created.getTenantId(), created.getApprovalNo(),
                ApprovalAction.RESUBMIT, "USER", withdrawn.getVersion());
        assertEquals("PENDING_MANAGER", resubmitted.getStatus());
    }

    @Test
    void concurrentDuplicateSubmissionsProduceOneApprovalAndOneEvent() throws Exception {
        var request = request("concurrent", "900");
        var pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) tasks.add(() -> commands.submit(request).approval().getId());
            List<String> ids = pool.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new RuntimeException(error); }
            }).toList();
            assertEquals(1, ids.stream().distinct().count());
            String id = ids.get(0);
            assertEquals(1, outbox.countByAggregateId(id));
            assertEquals(1, approvals.findByRequestId(request.idempotencyKey()).stream().count());
        } finally {
            pool.shutdownNow();
        }
    }

    private ApprovalTransactionService.ApprovalSubmission request(String marker, String budget) {
        UserAccount user = users.findByUsernameIgnoreCase("voyage").orElseThrow();
        return new ApprovalTransactionService.ApprovalSubmission("test-" + marker + "-" + UUID.randomUUID(),
                user.getId(), user.getTenantId(), "上海", LocalDate.of(2026, 9, 10),
                new BigDecimal(budget), "客户会议", "L1", "OTHER", "DOMESTIC");
    }
}
