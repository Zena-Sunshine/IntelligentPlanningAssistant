package com.voyageiq.business;

import com.voyageiq.business.service.PolicyAdministrationService;
import com.voyageiq.business.service.PolicyDecisionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "voyageiq.messaging.enabled=false"
})
class PolicyDecisionIntegrationTest {
    @Autowired PolicyDecisionService decisions;
    @Autowired PolicyAdministrationService administration;

    @BeforeEach void reset() { decisions.clearCacheAndStats(); }

    @Test
    void highLevelEmployeeWithinLimitUsesManagerOnlyRoute() {
        var result = decide("L3", "TIER1", "DOMESTIC", "2500");
        assertEquals("高职级一线城市标准", result.ruleName());
        assertFalse(result.overBudget());
        assertFalse(result.requiresFinance());
        assertEquals("MANAGER_ONLY", result.approvalRoute());
        assertTrue(result.snapshotJson().contains("\"policyVersion\":1"));
    }

    @Test
    void overBudgetRequestEscalatesToFinance() {
        var result = decide("L1", "OTHER", "DOMESTIC", "1300");
        assertEquals(new BigDecimal("1200.00"), result.maxBudget());
        assertTrue(result.overBudget());
        assertTrue(result.requiresFinance());
        assertEquals("MANAGER_THEN_FINANCE", result.approvalRoute());
    }

    @Test
    void wildcardInternationalRuleAlwaysRequiresFinance() {
        var result = decide("L9", "TIER1", "INTERNATIONAL", "3000");
        assertEquals("境外差旅财务复核", result.ruleName());
        assertFalse(result.overBudget());
        assertTrue(result.requiresFinance());
    }

    @Test
    void compiledCatalogLoadsRulesOnlyOnceForRepeatedDecisions() {
        for (int i = 0; i < 100; i++) decide("L1", "OTHER", "DOMESTIC", "900");
        assertEquals(1, decisions.databaseLoads());
    }

    @Test
    void baselineReloadsRulesForEveryDecision() {
        var request = request("L1", "OTHER", "DOMESTIC", "900", LocalDate.of(2026, 9, 10));
        for (int i = 0; i < 20; i++) decisions.decideBaseline(request);
        assertEquals(20, decisions.databaseLoads());
    }

    @Test
    void publishingNewEffectiveVersionInvalidatesOldCatalog() {
        administration.publishVersion("tenant-voyage", 2, LocalDate.of(2027, 1, 1), null);
        administration.addRule("tenant-voyage", 2, "2027统一标准", null, null,
                "DOMESTIC", new BigDecimal("2200"), false, 10);
        var result = decisions.decide(request("L1", "OTHER", "DOMESTIC", "1800",
                LocalDate.of(2027, 2, 1)));
        assertEquals(2, result.policyVersion());
        assertEquals("2027统一标准", result.ruleName());
        assertFalse(result.overBudget());
    }

    @Test
    void unknownTenantFailsClosedInsteadOfUsingAnotherTenantPolicy() {
        var request = new PolicyDecisionService.DecisionRequest("other-tenant",
                LocalDate.of(2026, 9, 10), "L1", "OTHER", "DOMESTIC", new BigDecimal("100"));
        assertThrows(IllegalStateException.class, () -> decisions.decide(request));
    }

    @Test
    void invalidPolicyEffectiveRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> administration.publishVersion(
                "tenant-voyage", 99, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1)));
    }

    private PolicyDecisionService.Decision decide(String level, String tier, String type, String budget) {
        return decisions.decide(request(level, tier, type, budget, LocalDate.of(2026, 9, 10)));
    }

    private PolicyDecisionService.DecisionRequest request(String level, String tier, String type,
                                                           String budget, LocalDate day) {
        return new PolicyDecisionService.DecisionRequest("tenant-voyage", day, level, tier, type,
                new BigDecimal(budget));
    }
}
