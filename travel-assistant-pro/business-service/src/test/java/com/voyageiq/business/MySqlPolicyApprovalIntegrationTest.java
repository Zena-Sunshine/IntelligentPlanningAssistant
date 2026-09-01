package com.voyageiq.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.repository.OutboxEventRepository;
import com.voyageiq.business.repository.TravelApprovalRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.service.ApprovalCommandService;
import com.voyageiq.business.service.ApprovalTransactionService;
import com.voyageiq.business.service.IdempotencyConflictException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in acceptance test against an isolated, real MySQL process. It verifies
 * the production migration dialect and the policy/idempotency/outbox transaction
 * together; the default unit suite remains independent from external services.
 */
@SpringBootTest(properties = "voyageiq.messaging.enabled=false")
@EnabledIfSystemProperty(named = "voyageiq.mysql.integration", matches = "true")
class MySqlPolicyApprovalIntegrationTest {
    @Autowired ApprovalCommandService commands;
    @Autowired UserAccountRepository users;
    @Autowired TravelApprovalRepository approvals;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("voyageiq.mysql.url",
                "jdbc:mysql://127.0.0.1:3307/voyageiq?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"));
        registry.add("spring.datasource.username", () -> System.getProperty("voyageiq.mysql.username", "voyageiq"));
        registry.add("spring.datasource.password", () -> System.getProperty("voyageiq.mysql.password", "voyageiq_dev"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Test
    void flywayPolicyIdempotencyAndOutboxRunOnRealMySql() throws Exception {
        String requestId = "mysql-integration-" + UUID.randomUUID();
        String userId = users.findByUsernameIgnoreCase("voyage").orElseThrow().getId();
        var request = new ApprovalTransactionService.ApprovalSubmission(
                requestId, userId, "tenant-voyage", "上海", LocalDate.of(2026, 9, 18),
                new BigDecimal("1600.00"), "MySQL 真实集成验收", "L1", "TIER1", "DOMESTIC");

        var first = commands.submit(request);
        var replay = commands.submit(request);
        var conflicting = new ApprovalTransactionService.ApprovalSubmission(
                requestId, userId, "tenant-voyage", "北京", request.travelDate(), request.budget(),
                request.reason(), request.employeeLevel(), request.cityTier(), request.travelType());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.approval().getId()).isEqualTo(first.approval().getId());
        assertThat(first.approval().getPolicyVersion()).isEqualTo(1);
        assertThat(first.approval().getPolicyRuleId()).isNotNull();
        assertThat(first.approval().isRequiresFinance()).isFalse();
        assertThat(first.approval().getPolicySnapshot()).contains("普通员工一线城市标准");
        assertThat(approvals.findByRequestId(requestId)).isPresent();
        assertThat(outbox.countByAggregateId(first.approval().getId())).isEqualTo(1);
        assertThatThrownBy(() -> commands.submit(conflicting))
                .isInstanceOf(IdempotencyConflictException.class);

        Integer flywayVersion = jdbc.queryForObject(
                "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1", Integer.class);
        Integer idempotencyIndexes = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name='travel_approval' " +
                        "AND index_name IN ('request_id','uk_approval_tenant_request')", Integer.class);
        String databaseVersion = jdbc.queryForObject("SELECT VERSION()", String.class);

        assertThat(flywayVersion).isEqualTo(3);
        assertThat(idempotencyIndexes).isEqualTo(2);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAt", Instant.now().toString());
        report.put("status", "PASSED_REAL_MYSQL");
        report.put("databaseVersion", databaseVersion);
        report.put("endpoint", "127.0.0.1:3307/voyageiq");
        report.put("flywayVersion", flywayVersion);
        report.put("checks", Map.of(
                "productionDialectMigrations", "V1 through V3 applied",
                "policyDecisionSnapshot", true,
                "samePayloadIdempotentReplay", true,
                "changedPayloadConflictRejected", true,
                "approvalRowsForLogicalRequest", 1,
                "outboxRowsForApproval", 1,
                "idempotencyIndexes", idempotencyIndexes));
        Path output = Path.of("..", "docs", "reports", "backend", "mysql-integration-status.json").normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("MYSQL_INTEGRATION=" + new ObjectMapper().writeValueAsString(report));
    }
}
