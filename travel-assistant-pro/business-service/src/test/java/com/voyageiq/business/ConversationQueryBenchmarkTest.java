package com.voyageiq.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.Conversation;
import com.voyageiq.business.domain.UserAccount;
import com.voyageiq.business.repository.ChatMessageRepository;
import com.voyageiq.business.repository.ConversationRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import com.voyageiq.business.service.ConversationService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:querybench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
class ConversationQueryBenchmarkTest {
    private static final String USERNAME = "query-benchmark";
    @Autowired ConversationService service;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired UserAccountRepository users;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PasswordEncoder encoder;

    private String userId;

    @BeforeEach
    void seed() {
        userId = users.findByUsernameIgnoreCase(USERNAME).map(UserAccount::getId).orElseGet(() -> {
            UserAccount user = new UserAccount(UUID.randomUUID().toString(), USERNAME, encoder.encode("irrelevant"),
                    "Query Benchmark", "tenant-benchmark", "USER", true, Instant.now());
            return users.save(user).getId();
        });
        if (conversations.findByUserIdAndDeletedAtIsNull(userId,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements() == 0) {
            for (int index = 0; index < 60; index++) {
                Conversation conversation = service.create(userId, "tenant-benchmark", "历史差旅事项 " + index);
                service.append(conversation.getId(), userId, "user", null,
                        "查询第 " + index + " 次差旅安排", null, null, null);
                service.append(conversation.getId(), userId, "assistant", "response_composer",
                        "已经完成第 " + index + " 次事项处理", "[]", "[]", "trace-" + index);
            }
        }
    }

    @Test
    void compareNPlusOneBaselineWithDenormalizedConversationProjection() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        List<Long> optimizedDurations = measure(30, () ->
                service.list(userId, "", 0, 50).getContent().forEach(Conversation::getLastMessagePreview));
        long optimizedSql = statistics.getPrepareStatementCount();

        statistics.clear();
        List<Long> baselineDurations = measure(30, () -> {
            List<Conversation> page = service.list(userId, "", 0, 50).getContent();
            page.forEach(value -> messages.findByConversationIdOrderByCreatedAtAsc(value.getId()).size());
        });
        long baselineSql = statistics.getPrepareStatementCount();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("dataset", Map.of("conversations", 60, "pageSize", 50, "iterations", 30));
        result.put("baseline", Map.of(
                "strategy", "list then load messages per conversation",
                "sqlPerIteration", baselineSql / 30.0,
                "p50Ms", percentile(baselineDurations, .50),
                "p95Ms", percentile(baselineDurations, .95)));
        result.put("optimized", Map.of(
                "strategy", "denormalized messageCount/lastMessagePreview projection",
                "sqlPerIteration", optimizedSql / 30.0,
                "p50Ms", percentile(optimizedDurations, .50),
                "p95Ms", percentile(optimizedDurations, .95)));

        Path report = Path.of("..", "docs", "reports", "backend-conversation-query.json").normalize();
        Files.createDirectories(report.getParent());
        Files.writeString(report, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("BACKEND_QUERY_BENCHMARK=" + new ObjectMapper().writeValueAsString(result));

        assertThat(optimizedSql / 30.0).isLessThanOrEqualTo(2.0);
        assertThat(baselineSql / 30.0).isGreaterThanOrEqualTo(50.0);
        assertThat(percentile(optimizedDurations, .95)).isLessThan(percentile(baselineDurations, .95));
    }

    private List<Long> measure(int iterations, Runnable operation) {
        List<Long> values = new ArrayList<>();
        for (int index = 0; index < iterations; index++) {
            long started = System.nanoTime();
            operation.run();
            values.add((System.nanoTime() - started) / 1_000_000);
        }
        return values;
    }

    private long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1));
    }
}

