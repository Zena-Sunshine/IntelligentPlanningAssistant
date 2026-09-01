package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "policy_version", uniqueConstraints =
        @UniqueConstraint(name = "uk_policy_version_tenant", columnNames = {"tenant_id", "version_no"}))
public class PolicyVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "version_no", nullable = false)
    private int versionNo;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PolicyVersion() {}

    public PolicyVersion(String tenantId, int versionNo, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.tenantId = tenantId;
        this.versionNo = versionNo;
        this.status = "PUBLISHED";
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public int getVersionNo() { return versionNo; }
    public String getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
}
