package com.voyageiq.business.repository;

import com.voyageiq.business.domain.ProcessedMessage;
import com.voyageiq.business.domain.ProcessedMessageId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {}
