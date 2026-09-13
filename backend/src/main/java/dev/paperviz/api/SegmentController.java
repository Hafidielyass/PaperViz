package dev.paperviz.api;

import dev.paperviz.ai.SegmentJobRunner;
import dev.paperviz.ai.SegmentService;
import dev.paperviz.domain.model.Enums.RenderStatus;
import dev.paperviz.rendering.RenderService;
import dev.paperviz.ai.SegmentWriterAi;
import dev.paperviz.config.PaperVizProperties;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.ingestion.IngestionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The explainer: the handful of rewritten segments a reader actually sees.
 */
@RestController
@RequestMapping("/api/papers/{paperId}")
public class SegmentController {

    private final SegmentJobRunner segmentJobs;
    private final SegmentService segments;
    private final PaperRepository papers;
    private final RenderService renders;
    private final PaperVizProperties props;

    public SegmentController(SegmentJobRunner segmentJobs,
                             SegmentService segments,
                             PaperRepository papers,
                             RenderService renders,
                             PaperVizProperties props) {
        this.segmentJobs = segmentJobs;
        this.segments = segments;
        this.papers = papers;
        this.renders = renders;
        this.props = props;
    }

    /** True when this segment has a finished video the reader can play. */
    private boolean hasVideo(UUID segmentId) {
        return renders.forSegment(segmentId)
                .filter(r -> r.getStatus() == RenderStatus.READY && r.getVideoPath() != null)
                .isPresent();
    }

    /**
     * Writes the explainer. Fire and forget — this is several minutes of model
     * time, so the client polls the paper for status.
     */
    @PostMapping("/write")
    public SegmentDtos.WriteStarted write(
            @PathVariable UUID paperId,
            @RequestParam(defaultValue = "5") int count,
            @RequestParam(defaultValue = "true") boolean storyboard) {

        Paper paper = requireOwned(paperId);

        if (paper.getStatus() == PaperStatus.WRITING) {
            throw new IngestionException("This paper is already being written.");
        }
        if (paper.getStatus() == PaperStatus.UPLOADED || paper.getStatus() == PaperStatus.PARSING) {
            throw new IngestionException(
                    "This paper is not parsed yet (currently " + paper.getStatus() + ").");
        }
        if (count < 2 || count > 10) {
            throw new IngestionException("Segment count must be between 2 and 10.");
        }

        segmentJobs.submit(paperId, count, storyboard);
        return new SegmentDtos.WriteStarted(paperId, count, storyboard,
                "Writing the explainer. This takes a few minutes.");
    }

    @GetMapping("/segments")
    public List<SegmentDtos.SegmentView> list(@PathVariable UUID paperId) {
        requireOwned(paperId);
        return segments.forPaper(paperId).stream()
                .map(s -> SegmentDtos.SegmentView.of(s, hasVideo(s.getId())))
                .toList();
    }

    /** The whole reader payload in one call: paper metadata plus every segment. */
    @GetMapping("/explainer")
    public SegmentDtos.Explainer explainer(@PathVariable UUID paperId) {
        Paper paper = requireOwned(paperId);
        return new SegmentDtos.Explainer(
                PaperDtos.PaperSummary.of(paper, 0),
                segments.forPaper(paperId).stream()
                        .map(s -> SegmentDtos.SegmentView.of(s, hasVideo(s.getId())))
                        .toList(),
                SegmentWriterAi.DEFAULT_SEGMENT_COUNT);
    }

    private Paper requireOwned(UUID id) {
        return papers.findByIdAndOwnerId(id, props.getDefaultOwnerId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such paper."));
    }
}
