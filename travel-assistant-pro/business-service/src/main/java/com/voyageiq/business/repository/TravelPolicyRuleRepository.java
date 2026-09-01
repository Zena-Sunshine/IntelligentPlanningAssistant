package com.voyageiq.business.repository;

import com.voyageiq.business.domain.TravelPolicyRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPolicyRuleRepository extends JpaRepository<TravelPolicyRule, Long> {
    List<TravelPolicyRule> findByTenantIdAndPolicyVersionAndEnabledTrueOrderByPriorityDescIdAsc(
            String tenantId, int policyVersion);
    long countByTenantIdAndPolicyVersion(String tenantId, int policyVersion);
}
