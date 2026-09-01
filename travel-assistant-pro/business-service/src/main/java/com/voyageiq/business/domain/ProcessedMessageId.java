package com.voyageiq.business.domain;

import java.io.Serializable;
import java.util.Objects;

public class ProcessedMessageId implements Serializable {
    private String eventId;
    private String consumerName;

    public ProcessedMessageId() {}
    public ProcessedMessageId(String eventId, String consumerName) {
        this.eventId = eventId;
        this.consumerName = consumerName;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProcessedMessageId value)) return false;
        return Objects.equals(eventId, value.eventId) && Objects.equals(consumerName, value.consumerName);
    }
    @Override public int hashCode() { return Objects.hash(eventId, consumerName); }
}
