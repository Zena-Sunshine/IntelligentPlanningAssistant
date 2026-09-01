package com.voyageiq.business.repository;

import com.voyageiq.business.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.status = :status and e.nextAttemptAt <= :now order by e.createdAt")
    List<OutboxEvent> claimable(@Param("status") String status, @Param("now") Instant now, Pageable pageable);
    List<OutboxEvent> findByStatusAndProcessingStartedAtLessThanOrderByCreatedAtAsc(
            String status, Instant threshold, Pageable pageable);
    long countByStatus(String status);
    long countByAggregateId(String aggregateId);
}
