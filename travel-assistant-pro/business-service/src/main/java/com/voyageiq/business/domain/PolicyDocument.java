package com.voyageiq.business.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "policy_document")
public class PolicyDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(nullable = false, length = 500)
    private String keywords;
    @Column(nullable = false, length = 160)
    private String source;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PolicyDocument() {}

    public PolicyDocument(String tenantId, String title, String content, String keywords, String source) {
        this.tenantId = tenantId;
        this.title = title;
        this.content = content;
        this.keywords = keywords;
        this.source = source;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getKeywords() { return keywords; }
    public String getSource() { return source; }
}
