package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "approval_event_projection")
public class ApprovalEventProjection {
    @Id @Column(name = "event_id", length = 36)
    private String eventId;
    @Column(name = "approval_id", nullable = false, length = 36)
    private String approvalId;
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ApprovalEventProjection() {}
    public ApprovalEventProjection(String eventId, String approvalId, String eventType) {
        this.eventId = eventId;
        this.approvalId = approvalId;
        this.eventType = eventType;
        this.receivedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public String getApprovalId() { return approvalId; }
    public String getEventType() { return eventType; }
    public Instant getReceivedAt() { return receivedAt; }
}
