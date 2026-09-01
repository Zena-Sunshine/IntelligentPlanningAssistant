package com.voyageiq.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.repository.ApprovalEventProjectionRepository;
import com.voyageiq.business.repository.OutboxEventRepository;
import com.voyageiq.business.repository.ProcessedMessageRepository;
import com.voyageiq.business.repository.TravelApprovalRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.service.ApprovalCommandService;
import com.voyageiq.business.service.ApprovalEventProjector;
import com.voyageiq.business.service.ApprovalTransactionService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "voyageiq.reliability.benchmark", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:reliabilitybench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "voyageiq.messaging.enabled=false",
        "logging.level.org.hibernate.SQL=ERROR"
})
class ApprovalReliabilityBenchmarkTest {
    private static final int UNIQUE_REQUESTS = 1_000;
    private static final int ATTEMPTS_PER_REQUEST = 10;
    private static final int TOTAL_ATTEMPTS = UNIQUE_REQUESTS * ATTEMPTS_PER_REQUEST;

    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalCommandService commands;
    @Autowired TravelApprovalRepository approvals;
    @Autowired UserAccountRepository users;
    @Autowired OutboxEventRepository outbox;
    @Autowired ApprovalEventProjector projector;
    @Autowired ProcessedMessageRepository processed;
    @Autowired ApprovalEventProjectionRepository projections;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        projections.deleteAll();
        processed.deleteAll();
        outbox.deleteAll();
        approvals.deleteAll();
        jdbc.execute("drop table if exists baseline_approval");
        jdbc.execute("create table baseline_approval (id bigint auto_increment primary key, request_id varchar(80) not null)");
    }

    @Test
    void measureDuplicatePreventionAndExactlyOnceProjection() throws Exception {
        long baselineStarted = System.nanoTime();
        for (int request = 0; request < UNIQUE_REQUESTS; request++) {
            String key = "baseline-" + request;
            for (int attempt = 0; attempt < ATTEMPTS_PER_REQUEST; attempt++) {
                jdbc.update("insert into baseline_approval(request_id) values (?)", key);
            }
        }
        long baselineNanos = System.nanoTime() - baselineStarted;
        int baselineRows = jdbc.queryForObject("select count(*) from baseline_approval", Integer.class);
        int baselineDistinct = jdbc.queryForObject(
                "select count(distinct request_id) from baseline_approval", Integer.class);
        int baselineDuplicates = baselineRows - baselineDistinct;

        long optimizedStarted = System.nanoTime();
        long replays = 0;
        String userId = users.findByUsernameIgnoreCase("voyage").orElseThrow().getId();
        for (int request = 0; request < UNIQUE_REQUESTS; request++) {
            var submission = new ApprovalTransactionService.ApprovalSubmission(
                    "optimized-" + request, userId, "tenant-voyage", "上海",
                    LocalDate.of(2026, 9, 18), new BigDecimal("1600"), "可靠性压测",
                    "L1", "TIER1", "DOMESTIC");
            for (int attempt = 0; attempt < ATTEMPTS_PER_REQUEST; attempt++) {
                if (commands.submit(submission).replayed()) replays++;
            }
        }
        long optimizedNanos = System.nanoTime() - optimizedStarted;
        long optimizedRows = approvals.count();
        long outboxRows = outbox.count();

        long projectionStarted = System.nanoTime();
        long applied = 0;
        for (var event : outbox.findAll()) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_REQUEST; attempt++) {
                if (projector.process(event.getId(), event.getPayload())) applied++;
            }
        }
        long projectionNanos = System.nanoTime() - projectionStarted;

        assertEquals(9_000, baselineDuplicates);
        assertEquals(UNIQUE_REQUESTS, optimizedRows);
        assertEquals(TOTAL_ATTEMPTS - UNIQUE_REQUESTS, replays);
        assertEquals(UNIQUE_REQUESTS, outboxRows);
        assertEquals(UNIQUE_REQUESTS, applied);
        assertEquals(UNIQUE_REQUESTS, processed.count());
        assertEquals(UNIQUE_REQUESTS, projections.count());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("environment", Map.of(
                "database", "H2 2.3 MySQL mode", "java", System.getProperty("java.version"),
                "availableProcessors", Runtime.getRuntime().availableProcessors()));
        report.put("workload", Map.of("uniqueRequests", UNIQUE_REQUESTS,
                "attemptsPerRequest", ATTEMPTS_PER_REQUEST, "submissionAttempts", TOTAL_ATTEMPTS,
                "consumerDeliveryAttempts", TOTAL_ATTEMPTS));
        report.put("baselineWithoutIdempotencyConstraint", Map.of(
                "rows", baselineRows, "duplicateRows", baselineDuplicates,
                "throughputAttemptsPerSecond", throughput(TOTAL_ATTEMPTS, baselineNanos)));
        report.put("optimizedCommandPath", Map.of(
                "approvalRows", optimizedRows, "idempotentReplays", replays,
                "duplicateApprovalRows", optimizedRows - UNIQUE_REQUESTS,
                "outboxRows", outboxRows, "approvalOutboxGap", optimizedRows - outboxRows,
                "throughputAttemptsPerSecond", throughput(TOTAL_ATTEMPTS, optimizedNanos)));
        report.put("idempotentConsumer", Map.of(
                "deliveries", TOTAL_ATTEMPTS, "sideEffectsApplied", applied,
                "duplicateSideEffects", projections.count() - UNIQUE_REQUESTS,
                "throughputDeliveriesPerSecond", throughput(TOTAL_ATTEMPTS, projectionNanos)));
        report.put("comparison", Map.of(
                "duplicateRowsBefore", baselineDuplicates, "duplicateRowsAfter", 0,
                "duplicateReductionPercent", 100.0, "outboxAtomicityPercent", 100.0,
                "consumerDuplicateSuppressionPercent", 100.0));
        report.put("scope", "Single-process repeat-delivery benchmark; real RabbitMQ is covered by a separate broker integration test.");

        Path reportPath = Path.of("..", "docs", "reports", "backend", "approval-reliability-benchmark.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.println("APPROVAL_RELIABILITY_BENCHMARK=" + objectMapper.writeValueAsString(report));
        assertTrue(Files.size(reportPath) > 0);
    }

    private static double throughput(long count, long nanos) {
        return Math.round(count / (nanos / 1_000_000_000.0) * 100.0) / 100.0;
    }
}
