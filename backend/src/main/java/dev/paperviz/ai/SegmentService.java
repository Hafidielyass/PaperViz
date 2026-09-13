package dev.paperviz.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paperviz.ai.AiModels.SegmentCopy;
import dev.paperviz.ai.AiModels.SegmentPlanItem;
import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;
import dev.paperviz.domain.model.Segment;
import dev.paperviz.domain.repo.ConceptRepository;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.domain.repo.SectionRepository;
import dev.paperviz.domain.repo.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The transactional half of building the explainer.
 *
 * Same split as everywhere else in the pipeline: short database units here, the
 * slow model calls outside any transaction in {@link SegmentJobRunner}.
 */
@Service
public class SegmentService {

    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);

    private final PaperRepository papers;
    private final SectionRepository sections;
    private final ConceptRepository concepts;
    private final SegmentRepository segments;
    private final ObjectMapper objectMapper;

    public SegmentService(PaperRepository papers,
                          SectionRepository sections,
                          ConceptRepository concepts,
                          SegmentRepository segments,
                          ObjectMapper objectMapper) {
        this.papers = papers;
        this.sections = sections;
        this.concepts = concepts;
        this.segments = segments;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<WriteTarget> beginWriting(UUID paperId) {
        Paper paper = papers.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("No such paper: " + paperId));

        if (paper.getStatus() == PaperStatus.WRITING) {
            log.info("paper {} is already being written, skipping", paperId);
            return Optional.empty();
        }

        paper.setStatus(PaperStatus.WRITING);
        paper.setStatusDetail("Choosing the moments that carry the paper");
        papers.save(paper);

        return Optional.of(new WriteTarget(paperId, paper.getTitle()));
    }

    @Transactional(readOnly = true)
    public List<Section> sections(UUID paperId) {
        return sections.findByPaperIdOrderByOrdinalAsc(paperId);
    }

    @Transactional(readOnly = true)
    public List<Concept> concepts(UUID paperId) {
        return concepts.findByPaperId(paperId);
    }

    /** Wipes any previous explainer for this paper; a rewrite replaces, never appends. */
    @Transactional
    public void clearSegments(UUID paperId) {
        segments.deleteByPaperId(paperId);
        segments.flush();
    }

    @Transactional
    public UUID saveSegment(UUID paperId, int ordinal, SegmentPlanItem plan, SegmentCopy copy) {
        Segment segment = new Segment();
        segment.setPaperId(paperId);
        segment.setOrdinal(ordinal);
        // The writer may improve the planned title; prefer its version.
        segment.setTitle(firstNonBlank(copy.title(), plan.title(), "Untitled"));
        segment.setBody(copy.body());
        segment.setKeyTakeaway(copy.keyTakeaway());
        segment.setLatex(blankToNull(copy.latex()));
        segment.setSourceOrdinals(plan.safeSourceOrdinals().toArray(new Integer[0]));
        return segments.save(segment).getId();
    }

    @Transactional
    public void attachStoryboard(UUID segmentId, Storyboard storyboard) {
        segments.findById(segmentId).ifPresent(segment -> {
            segment.setStoryboardJson(toJson(storyboard));
            segment.setNarration(storyboard.safeBeats().stream()
                    .map(StoryboardBeat::narration)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.joining(" ")));
            segments.save(segment);
        });
    }

    @Transactional
    public void markStoryboardFailed(UUID segmentId, List<String> problems) {
        segments.findById(segmentId).ifPresent(segment -> {
            segment.setStoryboardJson(toJson(new StoryboardFailure(problems)));
            segments.save(segment);
        });
    }

    @Transactional
    public void finishWriting(UUID paperId, int segmentCount, int storyboardCount) {
        papers.findById(paperId).ifPresent(paper -> {
            paper.setStatus(PaperStatus.WRITTEN);
            paper.setStatusDetail("%d segment(s) written, %d storyboarded"
                    .formatted(segmentCount, storyboardCount));
            papers.save(paper);
        });
    }

    @Transactional
    public void failWriting(UUID paperId, Throwable cause) {
        papers.findById(paperId).ifPresent(paper -> {
            paper.setStatus(PaperStatus.FAILED);
            String message = cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage();
            paper.setStatusDetail(message.length() > 500 ? message.substring(0, 500) : message);
            papers.save(paper);
        });
    }

    @Transactional(readOnly = true)
    public List<Segment> forPaper(UUID paperId) {
        return segments.findByPaperIdOrderByOrdinalAsc(paperId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise storyboard", e);
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record WriteTarget(UUID paperId, String paperTitle) {
    }

    private record StoryboardFailure(List<String> problems) {
    }
}
