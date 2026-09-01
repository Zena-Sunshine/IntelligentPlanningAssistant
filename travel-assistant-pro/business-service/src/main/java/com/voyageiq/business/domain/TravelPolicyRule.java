package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "travel_policy_rule")
public class TravelPolicyRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    @Column(name = "rule_name", nullable = false, length = 160)
    private String ruleName;
    @Column(name = "employee_level", length = 32)
    private String employeeLevel;
    @Column(name = "city_tier", length = 32)
    private String cityTier;
    @Column(name = "travel_type", length = 32)
    private String travelType;
    @Column(name = "max_budget", nullable = false, precision = 12, scale = 2)
    private BigDecimal maxBudget;
    @Column(name = "requires_finance", nullable = false)
    private boolean requiresFinance;
    @Column(nullable = false)
    private int priority;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TravelPolicyRule() {}

    public TravelPolicyRule(String tenantId, int policyVersion, String ruleName, String employeeLevel,
                            String cityTier, String travelType, BigDecimal maxBudget,
                            boolean requiresFinance, int priority) {
        this.tenantId = tenantId;
        this.policyVersion = policyVersion;
        this.ruleName = ruleName;
        this.employeeLevel = normalize(employeeLevel);
        this.cityTier = normalize(cityTier);
        this.travelType = normalize(travelType);
        this.maxBudget = maxBudget;
        this.requiresFinance = requiresFinance;
        this.priority = priority;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public boolean matches(String level, String tier, String type) {
        return matchesDimension(employeeLevel, level)
                && matchesDimension(cityTier, tier)
                && matchesDimension(travelType, type);
    }

    private static boolean matchesDimension(String configured, String actual) {
        return configured == null || configured.equalsIgnoreCase(actual);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() || "*".equals(value) ? null : value.trim().toUpperCase();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public int getPolicyVersion() { return policyVersion; }
    public String getRuleName() { return ruleName; }
    public String getEmployeeLevel() { return employeeLevel; }
    public String getCityTier() { return cityTier; }
    public String getTravelType() { return travelType; }
    public BigDecimal getMaxBudget() { return maxBudget; }
    public boolean isRequiresFinance() { return requiresFinance; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
}
