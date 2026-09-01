package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "travel_approval")
public class TravelApproval {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "approval_no", nullable = false, unique = true, length = 32)
    private String approvalNo;
    @Column(name = "request_id", nullable = false, unique = true, length = 80)
    private String requestId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(nullable = false, length = 80)
    private String destination;
    @Column(name = "travel_date", nullable = false, length = 20)
    private String travelDate;
    @Column(precision = 12, scale = 2)
    private BigDecimal budget;
    @Column(nullable = false, length = 500)
    private String reason;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "employee_level", nullable = false, length = 32)
    private String employeeLevel;
    @Column(name = "city_tier", nullable = false, length = 32)
    private String cityTier;
    @Column(name = "travel_type", nullable = false, length = 32)
    private String travelType;
    @Column(name = "policy_version")
    private Integer policyVersion;
    @Column(name = "policy_rule_id")
    private Long policyRuleId;
    @Column(name = "policy_snapshot", columnDefinition = "TEXT")
    private String policySnapshot;
    @Column(name = "decision_trace", columnDefinition = "TEXT")
    private String decisionTrace;
    @Column(name = "requires_finance", nullable = false)
    private boolean requiresFinance;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "lock_version", nullable = false)
    private long version;

    protected TravelApproval() {}

    public static TravelApproval create(String id, String approvalNo, String requestId, String userId,
                                        String tenantId, String destination, String travelDate,
                                        BigDecimal budget, String reason, String employeeLevel,
                                        String cityTier, String travelType, Integer policyVersion,
                                        Long policyRuleId, String policySnapshot, String decisionTrace,
                                        boolean requiresFinance) {
        TravelApproval value = new TravelApproval();
        value.id = id;
        value.approvalNo = approvalNo;
        value.requestId = requestId;
        value.userId = userId;
        value.tenantId = tenantId;
        value.destination = destination;
        value.travelDate = travelDate;
        value.budget = budget;
        value.reason = reason;
        value.status = ApprovalStatus.PENDING_MANAGER.name();
        value.employeeLevel = employeeLevel;
        value.cityTier = cityTier;
        value.travelType = travelType;
        value.policyVersion = policyVersion;
        value.policyRuleId = policyRuleId;
        value.policySnapshot = policySnapshot;
        value.decisionTrace = decisionTrace;
        value.requiresFinance = requiresFinance;
        value.createdAt = Instant.now();
        value.updatedAt = value.createdAt;
        return value;
    }

    public ApprovalStatus transition(ApprovalAction action) {
        ApprovalStatus current = ApprovalStatus.valueOf(status);
        ApprovalStatus next = switch (current) {
            case PENDING_MANAGER -> switch (action) {
                case APPROVE -> requiresFinance ? ApprovalStatus.PENDING_FINANCE : ApprovalStatus.APPROVED;
                case REJECT -> ApprovalStatus.REJECTED;
                case WITHDRAW -> ApprovalStatus.WITHDRAWN;
                default -> throw invalid(current, action);
            };
            case PENDING_FINANCE -> switch (action) {
                case APPROVE -> ApprovalStatus.APPROVED;
                case REJECT -> ApprovalStatus.REJECTED;
                case WITHDRAW -> ApprovalStatus.WITHDRAWN;
                default -> throw invalid(current, action);
            };
            case REJECTED, WITHDRAWN -> {
                if (action != ApprovalAction.RESUBMIT) throw invalid(current, action);
                yield ApprovalStatus.PENDING_MANAGER;
            }
            case APPROVED -> throw invalid(current, action);
        };
        status = next.name();
        updatedAt = Instant.now();
        return next;
    }

    private IllegalStateException invalid(ApprovalStatus current, ApprovalAction action) {
        return new IllegalStateException("illegal approval transition: " + current + " -> " + action);
    }

    public String getId() { return id; }
    public String getApprovalNo() { return approvalNo; }
    public String getRequestId() { return requestId; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getDestination() { return destination; }
    public String getTravelDate() { return travelDate; }
    public BigDecimal getBudget() { return budget; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getEmployeeLevel() { return employeeLevel; }
    public String getCityTier() { return cityTier; }
    public String getTravelType() { return travelType; }
    public Integer getPolicyVersion() { return policyVersion; }
    public Long getPolicyRuleId() { return policyRuleId; }
    public String getPolicySnapshot() { return policySnapshot; }
    public String getDecisionTrace() { return decisionTrace; }
    public boolean isRequiresFinance() { return requiresFinance; }
    public long getVersion() { return version; }
}
