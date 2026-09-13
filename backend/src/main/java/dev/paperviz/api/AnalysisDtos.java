package dev.paperviz.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.paperviz.domain.model.Concept;

import java.util.List;
import java.util.UUID;

/** Response shapes for concept extraction and storyboarding. */
public final class AnalysisDtos {

    private AnalysisDtos() {
    }

    public record ConceptView(
            UUID id,
            UUID sectionId,
            int ordinal,
            String title,
            String description,
            String conceptType,
            boolean animate,
            /* Already JSON in the database; embed it rather than double-encoding it. */
            @JsonRawValue String storyboard,
            String narration
    ) {
        public static ConceptView of(Concept concept) {
            return new ConceptView(
                    concept.getId(),
                    concept.getSectionId(),
                    concept.getOrdinal(),
                    concept.getTitle(),
                    concept.getDescription(),
                    concept.getConceptType().name(),
                    concept.isAnimate(),
                    concept.getStoryboardJson(),
                    concept.getNarration());
        }
    }

    public record AnalysisStarted(
            UUID paperId,
            UUID sectionId,
            boolean storyboard,
            String message
    ) {
    }

    public record SectionAnalysisResult(
            UUID sectionId,
            int conceptsFound,
            int storyboardsBuilt,
            long elapsedMs,
            List<ConceptView> concepts
    ) {
    }
}
