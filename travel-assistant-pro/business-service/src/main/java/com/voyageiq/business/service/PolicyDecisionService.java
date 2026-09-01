package com.voyageiq.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.voyageiq.business.domain.PolicyVersion;
import com.voyageiq.business.domain.TravelPolicyRule;
import com.voyageiq.business.repository.PolicyVersionRepository;
import com.voyageiq.business.repository.TravelPolicyRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyDecisionService {
    private final PolicyVersionRepository versions;
    private final TravelPolicyRuleRepository rules;
    private final ObjectMapper objectMapper;
    private final Cache<PolicyKey, List<TravelPolicyRule>> compiledRules = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(30, TimeUnit.MINUTES).build();
    private final Cache<VersionKey, PolicyVersion> effectiveVersions = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final AtomicLong databaseLoads = new AtomicLong();

    public PolicyDecisionService(PolicyVersionRepository versions, TravelPolicyRuleRepository rules,
                                 ObjectMapper objectMapper) {
        this.versions = versions;
        this.rules = rules;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Decision decide(DecisionRequest request) {
        PolicyVersion version = effectiveVersions.get(new VersionKey(request.tenantId(), request.travelDate()),
                key -> effectiveVersion(key.tenantId(), key.day()));
        PolicyKey key = new PolicyKey(request.tenantId(), version.getVersionNo());
        List<TravelPolicyRule> candidates = compiledRules.get(key, ignored -> loadRules(key));
        return evaluate(request, version, candidates, true);
    }

    @Transactional(readOnly = true)
    public Decision decideBaseline(DecisionRequest request) {
        PolicyVersion version = effectiveVersion(request.tenantId(), request.travelDate());
        PolicyKey key = new PolicyKey(request.tenantId(), version.getVersionNo());
        return evaluate(request, version, loadRules(key), false);
    }

    public void invalidate(String tenantId, int version) {
        compiledRules.invalidate(new PolicyKey(tenantId, version));
        effectiveVersions.asMap().keySet().removeIf(key -> key.tenantId().equals(tenantId));
    }

    public void clearCacheAndStats() {
        compiledRules.invalidateAll();
        effectiveVersions.invalidateAll();
        databaseLoads.set(0);
    }

    public long databaseLoads() { return databaseLoads.get(); }

    private PolicyVersion effectiveVersion(String tenantId, LocalDate day) {
        return versions.findEffective(tenantId, day).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no published policy version for tenant " + tenantId));
    }

    private List<TravelPolicyRule> loadRules(PolicyKey key) {
        databaseLoads.incrementAndGet();
        return rules.findByTenantIdAndPolicyVersionAndEnabledTrueOrderByPriorityDescIdAsc(
                key.tenantId(), key.version());
    }

    private Decision evaluate(DecisionRequest request, PolicyVersion version,
                              List<TravelPolicyRule> candidates, boolean cacheEnabled) {
        TravelPolicyRule matched = candidates.stream()
                .filter(rule -> rule.matches(request.employeeLevel(), request.cityTier(), request.travelType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no policy rule matched request dimensions"));
        boolean overBudget = request.budget() != null && request.budget().compareTo(matched.getMaxBudget()) > 0;
        boolean finance = matched.isRequiresFinance() || overBudget;
        String route = finance ? "MANAGER_THEN_FINANCE" : "MANAGER_ONLY";
        String explanation = "命中规则“" + matched.getRuleName() + "”；预算上限=" + matched.getMaxBudget()
                + "；申请预算=" + (request.budget() == null ? "未填写" : request.budget())
                + "；审批路径=" + route;
        Decision decision = new Decision(version.getVersionNo(), matched.getId(), matched.getRuleName(),
                matched.getMaxBudget(), overBudget, finance, route, explanation, cacheEnabled);
        return decision.withSnapshot(toJson(decision));
    }

    private String toJson(Decision value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("cannot serialize policy snapshot", error); }
    }

    private record PolicyKey(String tenantId, int version) {}
    private record VersionKey(String tenantId, LocalDate day) {}

    public record DecisionRequest(String tenantId, LocalDate travelDate, String employeeLevel,
                                  String cityTier, String travelType, BigDecimal budget) {}

    public record Decision(int policyVersion, Long ruleId, String ruleName, BigDecimal maxBudget,
                           boolean overBudget, boolean requiresFinance, String approvalRoute,
                           String explanation, boolean cacheEnabled, String snapshotJson) {
        Decision(int policyVersion, Long ruleId, String ruleName, BigDecimal maxBudget,
                 boolean overBudget, boolean requiresFinance, String approvalRoute,
                 String explanation, boolean cacheEnabled) {
            this(policyVersion, ruleId, ruleName, maxBudget, overBudget, requiresFinance,
                    approvalRoute, explanation, cacheEnabled, null);
        }
        Decision withSnapshot(String json) {
            return new Decision(policyVersion, ruleId, ruleName, maxBudget, overBudget,
                    requiresFinance, approvalRoute, explanation, cacheEnabled, json);
        }
    }
}
