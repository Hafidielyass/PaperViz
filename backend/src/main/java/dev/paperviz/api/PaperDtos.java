package dev.paperviz.api;

import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shapes for the Angular client. */
public final class PaperDtos {

    private PaperDtos() {
    }

    public record PaperSummary(
            UUID id,
            String title,
            String authors,
            String sourceType,
            String originalFilename,
            String status,
            String statusDetail,
            int sectionCount,
            OffsetDateTime createdAt
    ) {
        public static PaperSummary of(Paper paper, int sectionCount) {
            return new PaperSummary(
                    paper.getId(),
                    paper.getTitle(),
                    paper.getAuthors(),
                    paper.getSourceType().name(),
                    paper.getOriginalFilename(),
                    paper.getStatus().name(),
                    paper.getStatusDetail(),
                    sectionCount,
                    paper.getCreatedAt());
        }
    }

    public record SectionView(
            UUID id,
            int ordinal,
            String type,
            String heading,
            String text,
            String latex,
            int wordCount
    ) {
        public static SectionView of(Section section) {
            String text = section.getRawText() == null ? "" : section.getRawText();
            return new SectionView(
                    section.getId(),
                    section.getOrdinal(),
                    section.getSectionType().name(),
                    section.getHeading(),
                    text,
                    section.getLatex(),
                    text.isBlank() ? 0 : text.trim().split("\\s+").length);
        }
    }

    public record PaperDetail(
            PaperSummary paper,
            List<SectionView> sections
    ) {
    }

    /** Returned by the upload endpoint. */
    public record UploadResponse(
            UUID paperId,
            String status,
            boolean cacheHit,
            String message
    ) {
    }

    public record ApiError(
            String error,
            String message
    ) {
    }
}
