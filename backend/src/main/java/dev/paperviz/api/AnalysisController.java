package dev.paperviz.api;

import dev.paperviz.ai.AnalysisJobRunner;
import dev.paperviz.ai.AnalysisJobRunner.SectionAnalysis;
import dev.paperviz.config.PaperVizProperties;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.model.Section;
import dev.paperviz.domain.repo.ConceptRepository;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.domain.repo.SectionRepository;
import dev.paperviz.ingestion.IngestionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Concept extraction and storyboarding.
 *
 * Whole-paper analysis is fire-and-forget because it is minutes of model time;
 * single-section analysis is synchronous so the UI can show a result directly.
 */
@RestController
@RequestMapping("/api/papers/{paperId}")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final AnalysisJobRunner analysisJobs;
    private final PaperRepository papers;
    private final SectionRepository sections;
    private final ConceptRepository concepts;
    private final PaperVizProperties props;

    public AnalysisController(AnalysisJobRunner analysisJobs,
                              PaperRepository papers,
                              SectionRepository sections,
                              ConceptRepository concepts,
                              PaperVizProperties props) {
        this.analysisJobs = analysisJobs;
        this.papers = papers;
        this.sections = sections;
        this.concepts = concepts;
        this.props = props;
    }

    /** Kick off analysis of the whole paper. Returns immediately. */
    @PostMapping("/analyze")
    public AnalysisDtos.AnalysisStarted analyze(
            @PathVariable UUID paperId,
            @RequestParam(defaultValue = "true") boolean storyboard) {

        Paper paper = requireOwned(paperId);
        if (paper.getStatus() == PaperStatus.ANALYZING) {
            throw new IngestionException("This paper is already being analysed.");
        }
        if (paper.getStatus() != PaperStatus.PARSED && paper.getStatus() != PaperStatus.ANALYZED) {
            throw new IngestionException(
                    "This paper is not parsed yet (currently " + paper.getStatus() + ").");
        }

        analysisJobs.submit(paperId, storyboard);
        return new AnalysisDtos.AnalysisStarted(paperId, null, storyboard,
                "Analysis started. This takes a few minutes for a full paper.");
    }

    /**
     * Analyse exactly one section and wait for the result.
     *
     * This is the stage-3 demonstration path: one section is a handful of model
     * calls rather than a hundred, so it returns inside a normal request.
     */
    @PostMapping("/sections/{sectionId}/analyze")
    public AnalysisDtos.SectionAnalysisResult analyzeSection(
            @PathVariable UUID paperId,
            @PathVariable UUID sectionId,
            @RequestParam(defaultValue = "true") boolean storyboard) {

        Paper paper = requireOwned(paperId);
        Section section = sections.findById(sectionId)
                .filter(s -> s.getPaperId().equals(paperId))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such section."));

        long started = System.currentTimeMillis();
        SectionAnalysis result =
                analysisJobs.runOneSection(paperId, section, paper.getTitle(), storyboard);
        long elapsed = System.currentTimeMillis() - started;

        log.info("section {} analysed in {} ms: {} concepts, {} storyboards",
                section.getOrdinal(), elapsed, result.concepts(), result.storyboards());

        return new AnalysisDtos.SectionAnalysisResult(
                sectionId,
                result.concepts(),
                result.storyboards(),
                elapsed,
                concepts.findBySectionIdOrderByOrdinalAsc(sectionId).stream()
                        .map(AnalysisDtos.ConceptView::of)
                        .toList());
    }

    @GetMapping("/concepts")
    public List<AnalysisDtos.ConceptView> listConcepts(@PathVariable UUID paperId) {
        requireOwned(paperId);
        return concepts.findByPaperId(paperId).stream()
                .map(AnalysisDtos.ConceptView::of)
                .toList();
    }

    @GetMapping("/sections/{sectionId}/concepts")
    public List<AnalysisDtos.ConceptView> listSectionConcepts(@PathVariable UUID paperId,
                                                              @PathVariable UUID sectionId) {
        requireOwned(paperId);
        return concepts.findBySectionIdOrderByOrdinalAsc(sectionId).stream()
                .map(AnalysisDtos.ConceptView::of)
                .toList();
    }

    private Paper requireOwned(UUID id) {
        return papers.findByIdAndOwnerId(id, props.getDefaultOwnerId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such paper."));
    }
}
