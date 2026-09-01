package com.voyageiq.business.repository;

import com.voyageiq.business.domain.TravelApproval;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelApprovalRepository extends JpaRepository<TravelApproval, String> {
    Optional<TravelApproval> findByRequestId(String requestId);
    Optional<TravelApproval> findByTenantIdAndRequestId(String tenantId, String requestId);
    Optional<TravelApproval> findByApprovalNoAndTenantId(String approvalNo, String tenantId);
    List<TravelApproval> findByUserIdAndTenantIdOrderByCreatedAtDesc(String userId, String tenantId);
}
