package com.voyageiq.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:httpbench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class ConversationListHttpBenchmarkTest {
    private static final int CONVERSATION_COUNT = 200;
    private static final int WARMUP = 5;
    private static final int SAMPLES = 30;
    private static final int CONCURRENCY = 20;

    @LocalServerPort int port;

    @Test
    void conversationListHttpP95StaysBoundedUnderConcurrentReads() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper mapper = new ObjectMapper();
        String token = login(client, mapper);
        for (int index = 0; index < CONVERSATION_COUNT; index++) {
            postJson(client, token, "/api/v1/conversations", "{\"title\":\"HTTP基准会话 " + index + "\"}");
        }

        for (int index = 0; index < WARMUP; index++) {
            timedGet(client, token, "/api/v1/conversations?page=0&size=50");
        }

        List<Long> sequential = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            sequential.add(timedGet(client, token, "/api/v1/conversations?page=0&size=50"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Callable<Long>> jobs = new ArrayList<>();
        for (int index = 0; index < CONCURRENCY; index++) {
            jobs.add(() -> timedGet(client, token, "/api/v1/conversations?page=0&size=50"));
        }
        List<Long> concurrent = new ArrayList<>();
        for (Future<Long> future : pool.invokeAll(jobs)) {
            concurrent.add(future.get());
        }
        pool.shutdownNow();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("status", "HTTP_LIST_BENCHMARK_NOT_PRODUCTION_SLA");
        result.put("dataset", Map.of(
                "conversations", CONVERSATION_COUNT,
                "pageSize", 50,
                "sequentialSamples", SAMPLES,
                "concurrentWorkers", CONCURRENCY,
                "database", "H2 in-memory"));
        result.put("sequential", Map.of(
                "p50Ms", percentile(sequential, 0.50),
                "p95Ms", percentile(sequential, 0.95)));
        result.put("concurrent", Map.of(
                "p50Ms", percentile(concurrent, 0.50),
                "p95Ms", percentile(concurrent, 0.95),
                "errors", 0));

        Path report = Path.of("..", "docs", "reports", "backend-http-list.json").normalize();
        Files.createDirectories(report.getParent());
        Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        assertThat(percentile(sequential, 0.95)).isLessThan(500);
        assertThat(percentile(concurrent, 0.95)).isLessThan(1500);
    }

    private String login(HttpClient client, ObjectMapper mapper) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"voyage\",\"password\":\"Voyage@2026\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        return body.get("accessToken").asText();
    }

    private void postJson(HttpClient client, String token, String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private long timedGet(HttpClient client, String token, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertThat(response.statusCode()).isEqualTo(200);
        return elapsed;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1));
    }
}
