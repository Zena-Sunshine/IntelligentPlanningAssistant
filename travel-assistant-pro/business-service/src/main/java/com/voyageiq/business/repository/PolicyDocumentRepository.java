package com.voyageiq.business.repository;

import com.voyageiq.business.domain.PolicyDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
    List<PolicyDocument> findByTenantIdAndEnabledTrue(String tenantId);
}

