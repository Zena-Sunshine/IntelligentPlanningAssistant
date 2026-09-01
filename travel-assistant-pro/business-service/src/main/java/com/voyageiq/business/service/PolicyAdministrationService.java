package com.voyageiq.business.service;

import com.voyageiq.business.domain.PolicyVersion;
import com.voyageiq.business.domain.TravelPolicyRule;
import com.voyageiq.business.repository.PolicyVersionRepository;
import com.voyageiq.business.repository.TravelPolicyRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyAdministrationService {
    private final PolicyVersionRepository versions;
    private final TravelPolicyRuleRepository rules;
    private final PolicyDecisionService decisions;

    public PolicyAdministrationService(PolicyVersionRepository versions, TravelPolicyRuleRepository rules,
                                       PolicyDecisionService decisions) {
        this.versions = versions;
        this.rules = rules;
        this.decisions = decisions;
    }

    @Transactional
    public PolicyVersion publishVersion(String tenantId, int version, LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("policy effectiveTo must not be before effectiveFrom");
        }
        PolicyVersion existing = versions.findByTenantIdAndVersionNo(tenantId, version).orElse(null);
        if (existing != null) return existing;
        PolicyVersion saved = versions.save(new PolicyVersion(tenantId, version, from, to));
        decisions.invalidate(tenantId, version);
        return saved;
    }

    @Transactional
    public TravelPolicyRule addRule(String tenantId, int version, String name, String employeeLevel,
                                    String cityTier, String travelType, BigDecimal maxBudget,
                                    boolean requiresFinance, int priority) {
        if (versions.findByTenantIdAndVersionNo(tenantId, version).isEmpty()) {
            throw new IllegalArgumentException("policy version does not exist");
        }
        TravelPolicyRule saved = rules.save(new TravelPolicyRule(tenantId, version, name, employeeLevel,
                cityTier, travelType, maxBudget, requiresFinance, priority));
        decisions.invalidate(tenantId, version);
        return saved;
    }
}
