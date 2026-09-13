package dev.paperviz.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.paperviz.domain.model.Segment;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Response shapes for the explainer reader. */
public final class SegmentDtos {

    private SegmentDtos() {
    }

    public record SegmentView(
            UUID id,
            int ordinal,
            String title,
            /** Plain-language explainer prose. Paragraphs separated by a blank line. */
            String body,
            String keyTakeaway,
            String latex,
            List<Integer> sourceOrdinals,
            /* Already JSON in the database; embed rather than double-encoding. */
            @JsonRawValue String storyboard,
            String narration,
            /** Set only when a render is READY, so the reader can tell them apart. */
            String videoUrl
    ) {
        public static SegmentView of(Segment segment) {
            return of(segment, false);
        }

        public static SegmentView of(Segment segment, boolean hasVideo) {
            return new SegmentView(
                    segment.getId(),
                    segment.getOrdinal(),
                    segment.getTitle(),
                    segment.getBody(),
                    segment.getKeyTakeaway(),
                    segment.getLatex(),
                    segment.getSourceOrdinals() == null
                            ? List.of() : Arrays.asList(segment.getSourceOrdinals()),
                    segment.getStoryboardJson(),
                    segment.getNarration(),
                    hasVideo ? "/api/segments/" + segment.getId() + "/video" : null);
        }
    }

    public record Explainer(
            PaperDtos.PaperSummary paper,
            List<SegmentView> segments,
            int defaultSegmentCount
    ) {
    }

    public record WriteStarted(
            UUID paperId,
            int count,
            boolean storyboard,
            String message
    ) {
    }
}
