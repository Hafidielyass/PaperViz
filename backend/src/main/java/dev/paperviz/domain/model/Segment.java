package dev.paperviz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One piece of the finished explainer.
 *
 * A paper parses into 25-30 verbatim sections, which is the right input for the
 * model and the wrong thing to put in front of a reader. A segment is what the
 * reader actually sees: a rewritten title, a couple of plain-language
 * paragraphs, an optional equation, a takeaway, and one animation. There are
 * only a handful per paper.
 */
@Entity
@Table(name = "segments")
public class Segment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    /** Reading order in the explainer, 0-based. */
    @Column(nullable = false)
    private Integer ordinal;

    /** Rewritten for a reader, not the paper's printed heading. */
    @Column(nullable = false)
    private String title;

    /** Plain-language explainer prose, two or three short paragraphs. */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "key_takeaway", columnDefinition = "text")
    private String keyTakeaway;

    /** One display equation for this segment, or null. */
    @Column(columnDefinition = "text")
    private String latex;

    /**
     * Ordinals of the parsed sections this was written from. Makes a bad
     * rewrite traceable back to its source.
     */
    @Column(name = "source_ordinals", columnDefinition = "int[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private Integer[] sourceOrdinals = new Integer[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storyboard_json")
    private String storyboardJson;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPaperId() {
        return paperId;
    }

    public void setPaperId(UUID paperId) {
        this.paperId = paperId;
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

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getKeyTakeaway() {
        return keyTakeaway;
    }

    public void setKeyTakeaway(String keyTakeaway) {
        this.keyTakeaway = keyTakeaway;
    }

    public String getLatex() {
        return latex;
    }

    public void setLatex(String latex) {
        this.latex = latex;
    }

    public Integer[] getSourceOrdinals() {
        return sourceOrdinals;
    }

    public void setSourceOrdinals(Integer[] sourceOrdinals) {
        this.sourceOrdinals = sourceOrdinals == null ? new Integer[0] : sourceOrdinals;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
