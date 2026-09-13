package dev.paperviz.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paperviz.ai.AiModels.ConceptCandidate;
import dev.paperviz.ai.AiModels.Storyboard;
import dev.paperviz.ai.AiModels.StoryboardBeat;
import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Enums.ConceptType;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;
import dev.paperviz.domain.repo.ConceptRepository;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.domain.repo.SectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The transactional half of analysis.
 *
 * Same split as parsing: short database units here, slow LLM calls outside any
 * transaction in {@link AnalysisJobRunner}. A concept-extraction pass over a
 * whole paper is minutes of model time, which is not something to hold a
 * connection through.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final PaperRepository papers;
    private final SectionRepository sections;
    private final ConceptRepository concepts;
    private final ObjectMapper objectMapper;

    public AnalysisService(PaperRepository papers,
                           SectionRepository sections,
                           ConceptRepository concepts,
                           ObjectMapper objectMapper) {
        this.papers = papers;
        this.sections = sections;
        this.concepts = concepts;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<AnalysisTarget> beginAnalysis(UUID paperId) {
        Paper paper = papers.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("No such paper: " + paperId));

        if (paper.getStatus() == PaperStatus.ANALYZING) {
            log.info("paper {} is already being analysed, skipping", paperId);
            return Optional.empty();
        }
        if (paper.getStatus() != PaperStatus.PARSED && paper.getStatus() != PaperStatus.ANALYZED) {
            throw new IllegalStateException(
                    "Paper must be parsed before it can be analysed; it is " + paper.getStatus());
        }

        paper.setStatus(PaperStatus.ANALYZING);
        paper.setStatusDetail("Looking for concepts worth animating");
        papers.save(paper);

        return Optional.of(new AnalysisTarget(paperId, paper.getTitle()));
    }

    @Transactional(readOnly = true)
    public List<Section> sectionsToAnalyse(UUID paperId) {
        return sections.findByPaperIdOrderByOrdinalAsc(paperId).stream()
                .filter(this::worthAnalysing)
                .toList();
    }

    /**
     * Skips sections that cannot produce a useful animation, so we do not spend
     * an LLM call each on figure-caption fragments and bibliographies.
     */
    private boolean worthAnalysing(Section section) {
        String text = section.getRawText();
        if (text == null) {
            return false;
        }
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        return switch (section.getSectionType()) {
            case REFERENCES -> false;
            default -> words >= 40;
        };
    }

    @Transactional
    public void replaceConceptsForSection(UUID sectionId, List<ConceptCandidate> candidates) {
        concepts.deleteBySectionId(sectionId);
        concepts.flush();

        int ordinal = 0;
        for (ConceptCandidate candidate : candidates) {
            Concept concept = new Concept();
            concept.setSectionId(sectionId);
            concept.setOrdinal(ordinal++);
            concept.setTitle(trim(candidate.title(), 300));
            concept.setDescription(candidate.description());
            concept.setConceptType(candidate.conceptType() == null
                    ? ConceptType.OTHER : candidate.conceptType());
            concept.setAnimate(candidate.shouldAnimate());
            concepts.save(concept);
        }
    }

    @Transactional
    public void attachStoryboard(UUID conceptId, Storyboard storyboard) {
        concepts.findById(conceptId).ifPresent(concept -> {
            concept.setStoryboardJson(toJson(storyboard));
            concept.setNarration(storyboard.safeBeats().stream()
                    .map(StoryboardBeat::narration)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.joining(" ")));
            concepts.save(concept);
        });
    }

    /**
     * Records that storyboarding failed for this concept without losing the
     * concept itself — the UI shows it as un-animatable with a reason.
     */
    @Transactional
    public void markStoryboardFailed(UUID conceptId, List<String> problems) {
        concepts.findById(conceptId).ifPresent(concept -> {
            concept.setAnimate(false);
            concept.setStoryboardJson(toJson(new StoryboardFailure(problems)));
            concepts.save(concept);
        });
    }

    @Transactional
    public List<Concept> conceptsForSection(UUID sectionId) {
        return concepts.findBySectionIdOrderByOrdinalAsc(sectionId);
    }

    @Transactional
    public void finishAnalysis(UUID paperId, int conceptCount, int storyboardCount) {
        papers.findById(paperId).ifPresent(paper -> {
            paper.setStatus(PaperStatus.ANALYZED);
            paper.setStatusDetail("%d concept(s) found, %d storyboarded"
                    .formatted(conceptCount, storyboardCount));
            papers.save(paper);
        });
    }

    @Transactional
    public void failAnalysis(UUID paperId, Throwable cause) {
        papers.findById(paperId).ifPresent(paper -> {
            paper.setStatus(PaperStatus.FAILED);
            String message = cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage();
            paper.setStatusDetail(trim(message, 500));
            papers.save(paper);
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise storyboard", e);
        }
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record AnalysisTarget(UUID paperId, String paperTitle) {
    }

    /** Stored in storyboard_json when generation never produced a valid board. */
    private record StoryboardFailure(List<String> problems) {
    }
}
