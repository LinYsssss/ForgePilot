package com.example.codereview.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "requirement_link",
        uniqueConstraints = @UniqueConstraint(name = "uq_requirement_link",
                columnNames = {"requirementId", "linkType", "ref"}))
public class RequirementLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long requirementId;

    @Column(nullable = false, length = 32)
    private String linkType;

    @Column(nullable = false, length = 512)
    private String ref;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false)
    private Instant createdAt;

    protected RequirementLinkEntity() {
    }

    public RequirementLinkEntity(Long projectId, Long requirementId, String linkType, String ref, String source) {
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.linkType = linkType;
        this.ref = ref;
        this.source = source;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public String getLinkType() {
        return linkType;
    }

    public String getRef() {
        return ref;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
