package dev.paperviz.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.paperviz.domain.model.Enums.ConceptType;

import java.util.List;

/**
 * Structured-output shapes for the LLM.
 *
 * Every record is lenient about unknown fields: an 8B model will occasionally
 * add a key nobody asked for, and throwing away an otherwise good response over
 * that is not worth a retry cycle.
 */
public final class AiModels {

    private AiModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConceptCandidate(
            String title,
            ConceptType conceptType,
            String description,
            Boolean animate,
            String reason
    ) {
        public boolean shouldAnimate() {
            return animate == null || animate;
        }
    }

    /** Wrapper so the model returns an object, which small models handle far better than a bare array. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConceptExtraction(
            List<ConceptCandidate> concepts
    ) {
        public List<ConceptCandidate> safeConcepts() {
            return concepts == null ? List.of() : concepts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoryboardBeat(
            Integer order,
            Integer seconds,
            String visual,
            String narration,
            String latex
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Storyboard(
            String title,
            String summary,
            Integer totalSeconds,
            List<StoryboardBeat> beats
    ) {
        public List<StoryboardBeat> safeBeats() {
            return beats == null ? List.of() : beats;
        }
    }
}
