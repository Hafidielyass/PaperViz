package dev.paperviz.domain.model;

import dev.paperviz.domain.model.Enums.ConceptType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "concepts")
public class Concept {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "concept_type", nullable = false)
    private ConceptType conceptType = ConceptType.OTHER;

    /** False means the LLM judged this plain text — no animation is generated. */
    @Column(nullable = false)
    private boolean animate = false;

    /** Raw storyboard JSON as produced by the storyboarding prompt. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storyboard_json")
    private String storyboardJson;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public void setSectionId(UUID sectionId) {
        this.sectionId = sectionId;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ConceptType getConceptType() {
        return conceptType;
    }

    public void setConceptType(ConceptType conceptType) {
        this.conceptType = conceptType;
    }

    public boolean isAnimate() {
        return animate;
    }

    public void setAnimate(boolean animate) {
        this.animate = animate;
    }

    public String getStoryboardJson() {
        return storyboardJson;
    }

    public void setStoryboardJson(String storyboardJson) {
        this.storyboardJson = storyboardJson;
    }

    public String getNarration() {
        return narration;
    }

    public void setNarration(String narration) {
        this.narration = narration;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
