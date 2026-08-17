package com.example.codereview.requirement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "acceptance_criterion",
        uniqueConstraints = @UniqueConstraint(name = "uq_acceptance_criterion_seq", columnNames = {"requirementId", "seq"}))
public class AcceptanceCriterionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requirementId;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 2000)
    private String text;

    protected AcceptanceCriterionEntity() {
    }

    public AcceptanceCriterionEntity(Long requirementId, Integer seq, String text) {
        this.requirementId = requirementId;
        this.seq = seq;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getText() {
        return text;
    }
}
