package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@IdClass(ProcessedMessageId.class)
@Table(name = "processed_message")
public class ProcessedMessage {
    @Id @Column(name = "event_id", length = 36)
    private String eventId;
    @Id @Column(name = "consumer_name", length = 80)
    private String consumerName;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {}
    public ProcessedMessage(String eventId, String consumerName) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.processedAt = Instant.now();
    }
}
