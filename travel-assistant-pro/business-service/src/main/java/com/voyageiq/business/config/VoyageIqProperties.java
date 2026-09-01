package com.voyageiq.business.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voyageiq")
public record VoyageIqProperties(Security security, Agent agent, Cors cors, Messaging messaging) {
    public record Security(String jwtSecret, Duration tokenTtl, String internalServiceKey) {}
    public record Agent(String baseUrl, Duration connectTimeout, Duration responseTimeout) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Messaging(boolean enabled, int publishBatchSize, Duration processingTimeout) {}
}
