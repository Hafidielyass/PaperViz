package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.ConceptCandidate;
import dev.paperviz.ai.AnalysisService.AnalysisTarget;
import dev.paperviz.ai.PaperAnalysisAi.StoryboardResult;
import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs concept extraction and storyboarding off the request thread.
 *
 * A separate bean from {@link AnalysisService} for the same reason parsing is
 * split: {@code @Async} and {@code @Transactional} are both proxy-based, and a
 * self-invocation between them loses one of the two.
 */
@Component
public class AnalysisJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobRunner.class);

    private final AnalysisService analysis;
    private final PaperAnalysisAi ai;

    public AnalysisJobRunner(AnalysisService analysis, PaperAnalysisAi ai) {
        this.analysis = analysis;
        this.ai = ai;
    }

    @Async("paperVizExecutor")
    public void submit(UUID paperId, boolean storyboard) {
        run(paperId, storyboard);
    }

    /**
     * @param storyboard when false, only concept extraction runs. Useful for a
     *                   fast first pass over a long paper before committing to
     *                   the much slower storyboarding calls.
     */
    public void run(UUID paperId, boolean storyboard) {
        Optional<AnalysisTarget> claimed;
        try {
            claimed = analysis.beginAnalysis(paperId);
        } catch (Exception e) {
            log.error("could not start analysis for paper {}", paperId, e);
            analysis.failAnalysis(paperId, e);
            return;
        }
        if (claimed.isEmpty()) {
            return;
        }
        AnalysisTarget target = claimed.get();

        try {
            List<Section> sections = analysis.sectionsToAnalyse(paperId);
            log.info("analysing paper {}: {} candidate section(s)", paperId, sections.size());

            int conceptCount = 0;
            int storyboardCount = 0;

            for (Section section : sections) {
                // Outside any transaction on purpose: this is a model call.
                List<ConceptCandidate> candidates = ai.extractConcepts(target.paperTitle(), section);
                analysis.replaceConceptsForSection(section.getId(), candidates);
                conceptCount += candidates.size();

                if (!storyboard || candidates.isEmpty()) {
                    continue;
                }

                List<Concept> saved = analysis.conceptsForSection(section.getId());
                for (int i = 0; i < saved.size() && i < candidates.size(); i++) {
                    Concept concept = saved.get(i);
                    StoryboardResult result =
                            ai.buildStoryboard(target.paperTitle(), section, candidates.get(i));

                    if (result.ok()) {
                        analysis.attachStoryboard(concept.getId(), result.storyboard());
                        storyboardCount++;
                    } else {
                        log.warn("storyboarding failed for concept '{}': {}",
                                concept.getTitle(), result.problems());
                        analysis.markStoryboardFailed(concept.getId(), result.problems());
                    }
                }
            }

            analysis.finishAnalysis(paperId, conceptCount, storyboardCount);
            log.info("paper {} analysed: {} concepts, {} storyboards",
                    paperId, conceptCount, storyboardCount);
        } catch (Exception e) {
            log.error("analysis failed for paper {}", paperId, e);
            analysis.failAnalysis(paperId, e);
        }
    }

    /** Analyse a single section — the stage-3 demonstration path and a debugging aid. */
    public SectionAnalysis runOneSection(UUID paperId, Section section, String paperTitle, boolean storyboard) {
        List<ConceptCandidate> candidates = ai.extractConcepts(paperTitle, section);
        analysis.replaceConceptsForSection(section.getId(), candidates);

        int storyboarded = 0;
        if (storyboard && !candidates.isEmpty()) {
            List<Concept> saved = analysis.conceptsForSection(section.getId());
            for (int i = 0; i < saved.size() && i < candidates.size(); i++) {
                StoryboardResult result = ai.buildStoryboard(paperTitle, section, candidates.get(i));
                if (result.ok()) {
                    analysis.attachStoryboard(saved.get(i).getId(), result.storyboard());
                    storyboarded++;
                } else {
                    analysis.markStoryboardFailed(saved.get(i).getId(), result.problems());
                }
            }
        }
        return new SectionAnalysis(candidates.size(), storyboarded);
    }

    public record SectionAnalysis(int concepts, int storyboards) {
    }
}
