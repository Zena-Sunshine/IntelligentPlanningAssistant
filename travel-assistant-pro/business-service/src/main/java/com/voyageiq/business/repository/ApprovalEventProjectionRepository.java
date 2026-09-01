package com.voyageiq.business.repository;

import com.voyageiq.business.domain.ApprovalEventProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalEventProjectionRepository extends JpaRepository<ApprovalEventProjection, String> {
    long countByApprovalId(String approvalId);
}
