package dev.paperviz.domain.model;

import dev.paperviz.domain.model.Enums.RenderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "renders")
public class Render {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "concept_id", nullable = false, unique = true)
    private UUID conceptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RenderStatus status = RenderStatus.PENDING;

    /** The Manim source that actually rendered — kept for debugging and retry diffs. */
    @Column(name = "manim_code", columnDefinition = "text")
    private String manimCode;

    @Column(name = "video_path")
    private String videoPath;

    @Column(name = "narration_path")
    private String narrationPath;

    @Column(name = "duration_seconds")
    private BigDecimal durationSeconds;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** sha256(paper hash + storyboard) — a hit here skips generation and rendering entirely. */
    @Column(name = "cache_key", length = 64)
    private String cacheKey;

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

    public UUID getConceptId() {
        return conceptId;
    }

    public void setConceptId(UUID conceptId) {
        this.conceptId = conceptId;
    }

    public RenderStatus getStatus() {
        return status;
    }

    public void setStatus(RenderStatus status) {
        this.status = status;
    }

    public String getManimCode() {
        return manimCode;
    }

    public void setManimCode(String manimCode) {
        this.manimCode = manimCode;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getNarrationPath() {
        return narrationPath;
    }

    public void setNarrationPath(String narrationPath) {
        this.narrationPath = narrationPath;
    }

    public BigDecimal getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(BigDecimal durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
