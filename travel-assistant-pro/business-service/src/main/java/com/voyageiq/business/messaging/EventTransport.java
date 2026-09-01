package com.voyageiq.business.messaging;

import com.voyageiq.business.domain.OutboxEvent;

public interface EventTransport {
    void publish(OutboxEvent event) throws Exception;
}
