package com.voyageiq.business.repository;

import com.voyageiq.business.domain.PolicyVersion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {
    @Query("select p from PolicyVersion p where p.tenantId = :tenant and p.status = 'PUBLISHED' " +
            "and p.effectiveFrom <= :day and (p.effectiveTo is null or p.effectiveTo >= :day) " +
            "order by p.versionNo desc")
    List<PolicyVersion> findEffective(@Param("tenant") String tenantId, @Param("day") LocalDate day);
    Optional<PolicyVersion> findByTenantIdAndVersionNo(String tenantId, int versionNo);
}
