package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Version @Column(name = "lock_version", nullable = false)
    private long version;

    protected OutboxEvent() {}

    public static OutboxEvent create(String aggregateId, String eventType, String payload) {
        OutboxEvent value = new OutboxEvent();
        value.id = UUID.randomUUID().toString();
        value.aggregateType = "TravelApproval";
        value.aggregateId = aggregateId;
        value.eventType = eventType;
        value.payload = payload;
        value.status = OutboxStatus.PENDING.name();
        value.createdAt = Instant.now();
        value.nextAttemptAt = value.createdAt;
        return value;
    }

    public void claim() {
        if (OutboxStatus.PUBLISHED.name().equals(status)) throw new IllegalStateException("published event cannot be claimed");
        status = OutboxStatus.PROCESSING.name();
        processingStartedAt = Instant.now();
        attemptCount++;
    }

    public void markPublished() {
        status = OutboxStatus.PUBLISHED.name();
        publishedAt = Instant.now();
        processingStartedAt = null;
        lastError = null;
    }

    public void markFailed(String error) {
        status = OutboxStatus.PENDING.name();
        processingStartedAt = null;
        lastError = error == null ? "unknown" : error.substring(0, Math.min(500, error.length()));
        long seconds = Math.min(60, 1L << Math.min(attemptCount, 6));
        nextAttemptAt = Instant.now().plusSeconds(seconds);
    }

    public void recover() {
        status = OutboxStatus.PENDING.name();
        processingStartedAt = null;
        nextAttemptAt = Instant.now();
        lastError = "recovered stale processing event";
    }

    public boolean stale(Duration timeout, Instant now) {
        return OutboxStatus.PROCESSING.name().equals(status) && processingStartedAt != null
                && processingStartedAt.plus(timeout).isBefore(now);
    }

    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getPublishedAt() { return publishedAt; }
}
