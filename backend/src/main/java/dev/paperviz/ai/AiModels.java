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

    // --- the explainer plan: which handful of moments carry the paper --------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentPlanItem(
            Integer ordinal,
            String title,
            String focus,
            List<Integer> sourceOrdinals
    ) {
        public List<Integer> safeSourceOrdinals() {
            return sourceOrdinals == null ? List.of() : sourceOrdinals;
        }
    }

    /** Wrapper object — small models handle a named list far better than a bare array. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentPlan(List<SegmentPlanItem> segments) {
        public List<SegmentPlanItem> safeSegments() {
            return segments == null ? List.of() : segments;
        }
    }

    /** The written explainer text for one segment. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentCopy(
            String title,
            String body,
            String keyTakeaway,
            String latex
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoryboardBeat(
            Integer order,
            Integer seconds,
            /** Prose description of the shot — always present, and what the layout gate reads. */
            String visual,
            String narration,
            /** Convenience for a bare equation beat; superseded by {@code scene} when set. */
            String latex,
            /** Typed payload the renderer builds directly. Null falls back to FREEFORM. */
            SceneSpec.Visual scene
    ) {
        /** The effective scene, promoting a bare latex field to a typed equation. */
        public SceneSpec.Visual effectiveScene() {
            if (scene != null && scene.kind() != null) {
                return scene;
            }
            if (latex != null && !latex.isBlank()) {
                return new SceneSpec.Visual(
                        SceneSpec.VisualKind.EQUATION, null, latex, null, null, null);
            }
            return new SceneSpec.Visual(
                    SceneSpec.VisualKind.FREEFORM, null, null, null, null, visual);
        }
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
