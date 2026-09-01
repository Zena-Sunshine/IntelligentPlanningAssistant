package com.voyageiq.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyageiq.business.domain.Conversation;
import com.voyageiq.business.repository.ChatMessageRepository;
import com.voyageiq.business.service.ConversationService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in benchmark against an isolated real MySQL instance.
 *
 * Run with -Dvoyageiq.mysql.benchmark=true and the three voyageiq.mysql.*
 * connection properties. It is intentionally excluded from the default suite
 * because a developer's existing database must never be mutated implicitly.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
@EnabledIfSystemProperty(named = "voyageiq.mysql.benchmark", matches = "true")
class MySqlConversationScaleBenchmarkTest {
    private static final int CONVERSATION_COUNT = 10_000;
    private static final int MESSAGES_PER_CONVERSATION = 10;
    private static final int MESSAGE_COUNT = CONVERSATION_COUNT * MESSAGES_PER_CONVERSATION;
    private static final int PAGE_SIZE = 50;
    private static final int ITERATIONS = 30;
    private static final String USER_ID = "mysql-scale-benchmark-user";

    @Autowired ConversationService service;
    @Autowired ChatMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> required("voyageiq.mysql.url"));
        registry.add("spring.datasource.username", () -> required("voyageiq.mysql.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("voyageiq.mysql.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @Test
    void compareNPlusOneWithProjectionAtTenThousandConversationsAndOneHundredThousandMessages() throws Exception {
        seed();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE user_id=?", Long.class, USER_ID))
                .isEqualTo(CONVERSATION_COUNT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE user_id=?", Long.class, USER_ID))
                .isEqualTo(MESSAGE_COUNT);

        for (int index = 0; index < 5; index++) {
            service.list(USER_ID, "", 0, PAGE_SIZE).getContent();
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<Long> optimizedDurations = measure(() ->
                service.list(USER_ID, "", 0, PAGE_SIZE).getContent().forEach(Conversation::getLastMessagePreview));
        double optimizedSql = statistics.getPrepareStatementCount() / (double) ITERATIONS;

        statistics.clear();
        List<Long> baselineDurations = measure(() -> {
            List<Conversation> page = service.list(USER_ID, "", 0, PAGE_SIZE).getContent();
            page.forEach(value -> messages.findByConversationIdOrderByCreatedAtAsc(value.getId()).size());
        });
        double baselineSql = statistics.getPrepareStatementCount() / (double) ITERATIONS;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "LOCAL_REAL_MYSQL_SCALE_BENCHMARK_NOT_PRODUCTION_SLA");
        result.put("generatedAt", Instant.now().toString());
        result.put("database", Map.of(
                "version", jdbc.queryForObject("SELECT VERSION()", String.class),
                "url", redact(required("voyageiq.mysql.url"))));
        result.put("dataset", Map.of(
                "conversations", CONVERSATION_COUNT,
                "messages", MESSAGE_COUNT,
                "pageSize", PAGE_SIZE,
                "iterations", ITERATIONS,
                "warmups", 5));
        result.put("baseline", metrics("list plus one message query per conversation", baselineSql, baselineDurations));
        result.put("optimized", metrics("denormalized messageCount/lastMessagePreview projection", optimizedSql, optimizedDurations));

        Path report = Path.of("..", "docs", "reports", "backend-mysql-scale-benchmark.json").normalize();
        Files.createDirectories(report.getParent());
        Files.writeString(report, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("MYSQL_SCALE_BENCHMARK=" + new ObjectMapper().writeValueAsString(result));

        assertThat(optimizedSql).isLessThanOrEqualTo(2.0);
        assertThat(baselineSql).isGreaterThanOrEqualTo(50.0);
        assertThat(percentile(optimizedDurations, .95)).isLessThan(percentile(baselineDurations, .95));
    }

    private void seed() {
        jdbc.update("DELETE FROM chat_message WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM conversation WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM user_account WHERE id=?", USER_ID);
        jdbc.update("INSERT INTO user_account(id,username,password_hash,display_name,tenant_id,role,enabled,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)", USER_ID, "mysql-scale-benchmark", "not-used", "MySQL Scale Benchmark",
                "tenant-benchmark", "USER", true, Timestamp.from(Instant.now()));

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            connection.setAutoCommit(false);
            try (PreparedStatement conversation = connection.prepareStatement(
                    "INSERT INTO conversation(id,user_id,tenant_id,title,message_count,last_message_preview," +
                            "last_message_at,created_at,updated_at,deleted_at,version) VALUES(?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement message = connection.prepareStatement(
                    "INSERT INTO chat_message(id,conversation_id,user_id,role,agent_key,content,cards_json,intents," +
                            "trace_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                Timestamp now = Timestamp.from(Instant.now());
                int messageBatch = 0;
                for (int conversationIndex = 0; conversationIndex < CONVERSATION_COUNT; conversationIndex++) {
                    String conversationId = "scale-conv-" + String.format("%05d", conversationIndex);
                    conversation.setString(1, conversationId);
                    conversation.setString(2, USER_ID);
                    conversation.setString(3, "tenant-benchmark");
                    conversation.setString(4, "历史差旅会话 " + conversationIndex);
                    conversation.setInt(5, MESSAGES_PER_CONVERSATION);
                    conversation.setString(6, "最后一条消息 " + conversationIndex);
                    conversation.setTimestamp(7, now);
                    conversation.setTimestamp(8, now);
                    conversation.setTimestamp(9, now);
                    conversation.setTimestamp(10, null);
                    conversation.setLong(11, 0);
                    conversation.addBatch();

                    for (int messageIndex = 0; messageIndex < MESSAGES_PER_CONVERSATION; messageIndex++) {
                        message.setString(1, "scale-msg-" + String.format("%05d-%02d", conversationIndex, messageIndex));
                        message.setString(2, conversationId);
                        message.setString(3, USER_ID);
                        message.setString(4, messageIndex % 2 == 0 ? "user" : "assistant");
                        message.setString(5, messageIndex % 2 == 0 ? null : "response_composer");
                        message.setString(6, "规模基准消息 " + conversationIndex + "-" + messageIndex);
                        message.setString(7, "[]");
                        message.setString(8, "travel_search");
                        message.setString(9, "scale-trace-" + conversationIndex);
                        message.setTimestamp(10, now);
                        message.addBatch();
                        messageBatch++;
                    }
                    if ((conversationIndex + 1) % 500 == 0) {
                        conversation.executeBatch();
                    }
                    if (messageBatch >= 5_000) {
                        message.executeBatch();
                        messageBatch = 0;
                    }
                }
                conversation.executeBatch();
                message.executeBatch();
                connection.commit();
            }
            return null;
        });
    }

    private List<Long> measure(Runnable operation) {
        List<Long> values = new ArrayList<>();
        for (int index = 0; index < ITERATIONS; index++) {
            long started = System.nanoTime();
            operation.run();
            values.add((System.nanoTime() - started) / 1_000_000);
        }
        return values;
    }

    private Map<String, Object> metrics(String strategy, double sql, List<Long> durations) {
        return Map.of("strategy", strategy, "sqlPerIteration", sql,
                "p50Ms", percentile(durations, .50), "p95Ms", percentile(durations, .95));
    }

    private long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1));
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing system property: " + key);
        return value;
    }

    private static String redact(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }
}
