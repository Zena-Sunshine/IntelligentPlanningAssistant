package com.voyageiq.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.PolicyVersion;
import com.voyageiq.business.domain.TravelPolicyRule;
import com.voyageiq.business.repository.PolicyVersionRepository;
import com.voyageiq.business.repository.TravelPolicyRuleRepository;
import com.voyageiq.business.service.PolicyDecisionService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "voyageiq.policy.benchmark", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:policybench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=ERROR",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "voyageiq.messaging.enabled=false"
})
class PolicyDecisionBenchmarkTest {
    private static final String TENANT = "benchmark-tenant";
    private static final int RULES = 200;
    private static final int REQUESTS = 10_000;
    @Autowired PolicyVersionRepository versions;
    @Autowired TravelPolicyRuleRepository rules;
    @Autowired PolicyDecisionService decisions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        versions.save(new PolicyVersion(TENANT, 1, LocalDate.of(2026, 1, 1), null));
        List<TravelPolicyRule> values = new ArrayList<>();
        String[] levels = {"L1", "L2", "L3", "L4"};
        String[] tiers = {"TIER1", "TIER2", "OTHER"};
        for (int i = 0; i < RULES - 1; i++) {
            values.add(new TravelPolicyRule(TENANT, 1, "policy-" + i,
                    levels[i % levels.length], tiers[(i / levels.length) % tiers.length], "DOMESTIC",
                    BigDecimal.valueOf(900 + i * 5L), i % 11 == 0, 1000 - i));
        }
        values.add(new TravelPolicyRule(TENANT, 1, "fallback", null, null, "DOMESTIC",
                new BigDecimal("1000"), false, 1));
        rules.saveAll(values);
        decisions.clearCacheAndStats();
    }

    @Test
    void compareDatabaseReloadWithCompiledTenantVersionCatalog() throws Exception {
        List<PolicyDecisionService.DecisionRequest> inputs = inputs();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        decisions.clearCacheAndStats();
        statistics.clear();
        RunResult baseline = run(inputs, false);
        long baselineStatements = statistics.getPrepareStatementCount();
        long baselineLoads = decisions.databaseLoads();

        decisions.clearCacheAndStats();
        decisions.decide(inputs.get(0));
        statistics.clear();
        RunResult optimized = run(inputs, true);
        long optimizedStatements = statistics.getPrepareStatementCount();
        long optimizedLoads = decisions.databaseLoads();

        assertEquals(baseline.checksum(), optimized.checksum(), "optimization must not change decisions");
        assertEquals(REQUESTS, baselineLoads);
        assertEquals(1, optimizedLoads);
        assertTrue(optimizedStatements < baselineStatements);
        assertTrue(optimized.p95Micros() < baseline.p95Micros());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("environment", Map.of(
                "database", "H2 2.3 MySQL mode", "java", System.getProperty("java.version"),
                "availableProcessors", Runtime.getRuntime().availableProcessors()));
        report.put("dataset", Map.of("tenants", 1, "policyRules", RULES, "decisions", REQUESTS,
                "warmup", 1));
        report.put("baseline", metrics(baseline, baselineStatements, baselineLoads,
                "effective-version query + rule catalog query per decision"));
        report.put("optimized", metrics(optimized, optimizedStatements, optimizedLoads,
                "tenant/date version cache + tenant/version compiled rule catalog"));
        report.put("comparison", Map.of(
                "p95ReductionPercent", reduction(baseline.p95Micros(), optimized.p95Micros()),
                "throughputIncreasePercent", increase(baseline.throughput(), optimized.throughput()),
                "statementReductionPercent", reduction(baselineStatements, optimizedStatements),
                "decisionConsistencyPercent", 100.0));
        Path reportPath = Path.of("..", "docs", "reports", "backend", "policy-decision-benchmark.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.println("POLICY_DECISION_BENCHMARK=" + objectMapper.writeValueAsString(report));
    }

    private RunResult run(List<PolicyDecisionService.DecisionRequest> inputs, boolean optimized) {
        List<Long> durations = new ArrayList<>(inputs.size());
        long checksum = 17;
        long started = System.nanoTime();
        for (var input : inputs) {
            long callStarted = System.nanoTime();
            var result = optimized ? decisions.decide(input) : decisions.decideBaseline(input);
            durations.add(System.nanoTime() - callStarted);
            checksum = checksum * 31 + result.ruleName().hashCode();
            checksum = checksum * 31 + (result.requiresFinance() ? 1 : 0);
        }
        long elapsed = System.nanoTime() - started;
        Collections.sort(durations);
        return new RunResult(micros(durations.get(durations.size() / 2)),
                micros(durations.get((int) Math.ceil(durations.size() * 0.95) - 1)),
                inputs.size() / (elapsed / 1_000_000_000.0), checksum);
    }

    private List<PolicyDecisionService.DecisionRequest> inputs() {
        List<PolicyDecisionService.DecisionRequest> values = new ArrayList<>(REQUESTS);
        String[] levels = {"L1", "L2", "L3", "L4"};
        String[] tiers = {"TIER1", "TIER2", "OTHER"};
        for (int i = 0; i < REQUESTS; i++) {
            values.add(new PolicyDecisionService.DecisionRequest(TENANT, LocalDate.of(2026, 9, 1),
                    levels[i % levels.length], tiers[(i / 4) % tiers.length], "DOMESTIC",
                    BigDecimal.valueOf(800 + (i % 3000))));
        }
        return values;
    }

    private Map<String, Object> metrics(RunResult value, long statements, long loads, String strategy) {
        return Map.of("strategy", strategy, "p50Micros", value.p50Micros(),
                "p95Micros", value.p95Micros(), "throughputDecisionsPerSecond", round(value.throughput()),
                "preparedStatements", statements, "ruleCatalogLoads", loads);
    }

    private static double reduction(long before, long after) {
        return round((before - after) * 100.0 / Math.max(1, before));
    }
    private static double increase(double before, double after) {
        return round((after - before) * 100.0 / Math.max(0.0001, before));
    }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static long micros(long nanos) { return Math.max(1, nanos / 1000); }
    private record RunResult(long p50Micros, long p95Micros, double throughput, long checksum) {}
}
