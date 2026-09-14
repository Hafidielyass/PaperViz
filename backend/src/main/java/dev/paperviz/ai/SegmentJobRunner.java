package dev.paperviz.ai;

import dev.paperviz.ai.AiModels.SegmentCopy;
import dev.paperviz.ai.AiModels.SegmentPlanItem;
import dev.paperviz.ai.PaperAnalysisAi.StoryboardResult;
import dev.paperviz.ai.SegmentService.WriteTarget;
import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Section;
import dev.paperviz.rendering.RenderJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the explainer: choose the few moments that carry the paper, write each
 * one, and storyboard it.
 *
 * Separate bean from {@link SegmentService} so the {@code @Async} and
 * {@code @Transactional} proxies do not swallow each other, and so none of the
 * model calls sit inside a transaction.
 */
@Component
public class SegmentJobRunner {

    private static final Logger log = LoggerFactory.getLogger(SegmentJobRunner.class);

    private final SegmentService segments;
    private final SegmentWriterAi writer;
    private final PaperAnalysisAi analysisAi;
    private final RenderJobRunner renderJobs;

    public SegmentJobRunner(SegmentService segments,
                            SegmentWriterAi writer,
                            PaperAnalysisAi analysisAi,
                            RenderJobRunner renderJobs) {
        this.segments = segments;
        this.writer = writer;
        this.analysisAi = analysisAi;
        this.renderJobs = renderJobs;
    }

    @Async("paperVizExecutor")
    public void submit(UUID paperId, int targetCount, boolean storyboard) {
        run(paperId, targetCount, storyboard);
    }

    public Result run(UUID paperId, int targetCount, boolean storyboard) {
        Optional<WriteTarget> claimed;
        try {
            claimed = segments.beginWriting(paperId);
        } catch (Exception e) {
            log.error("could not start writing for paper {}", paperId, e);
            segments.failWriting(paperId, e);
            return new Result(0, 0, e.getMessage());
        }
        if (claimed.isEmpty()) {
            return new Result(0, 0, "Already being written.");
        }
        WriteTarget target = claimed.get();

        try {
            List<Section> allSections = segments.sections(paperId);
            List<Concept> candidates = segments.concepts(paperId);

            if (allSections.isEmpty()) {
                throw new IllegalStateException("This paper has no parsed sections to work from.");
            }

            // Model call, outside any transaction.
            List<SegmentPlanItem> plan =
                    writer.plan(target.paperTitle(), allSections, candidates);
            if (plan.isEmpty()) {
                throw new IllegalStateException(
                        "The model could not choose which parts of this paper to explain.");
            }

            Map<Integer, Section> byOrdinal = allSections.stream()
                    .collect(Collectors.toMap(Section::getOrdinal, Function.identity(),
                            (a, b) -> a, java.util.LinkedHashMap::new));

            segments.clearSegments(paperId);

            int written = 0;
            int storyboarded = 0;

            for (int i = 0; i < plan.size(); i++) {
                SegmentPlanItem item = plan.get(i);
                List<Section> sources = resolveSources(item, byOrdinal, allSections);

                SegmentCopy copy = writer.write(target.paperTitle(), item, sources);
                if (copy == null) {
                    log.warn("no copy produced for segment '{}', skipping", item.title());
                    continue;
                }

                UUID segmentId = segments.saveSegment(paperId, written, item, copy);
                written++;

                if (!storyboard) {
                    continue;
                }

                StoryboardResult result = analysisAi.buildStoryboardForSegment(
                        target.paperTitle(),
                        copy.title() == null || copy.title().isBlank() ? item.title() : copy.title(),
                        item.focus(),
                        joinText(sources),
                        joinFormulas(sources));

                if (result.ok()) {
                    segments.attachStoryboard(segmentId, result.storyboard());
                    storyboarded++;
                    // Queue the video immediately. A part without one is not
                    // finished, and asking the reader to press a button for
                    // something the pipeline could have done is busywork.
                    renderJobs.submitSegment(segmentId, "medium");
                } else {
                    log.warn("storyboard failed for segment '{}': {}", item.title(), result.problems());
                    segments.markStoryboardFailed(segmentId, result.problems());
                }
            }

            segments.finishWriting(paperId, written, storyboarded);
            log.info("paper {} written: {} segment(s), {} storyboarded", paperId, written, storyboarded);
            return new Result(written, storyboarded, null);

        } catch (Exception e) {
            log.error("writing failed for paper {}", paperId, e);
            segments.failWriting(paperId, e);
            return new Result(0, 0, e.getMessage());
        }
    }

    /**
     * Maps the plan's section numbers back to sections.
     *
     * The model sometimes cites an ordinal that does not exist. Falling back to
     * the whole paper would blow the context window, so an unusable citation
     * yields the abstract plus the first substantial section — enough to write
     * something honest rather than nothing.
     */
    private List<Section> resolveSources(SegmentPlanItem item,
                                         Map<Integer, Section> byOrdinal,
                                         List<Section> allSections) {
        Set<Integer> wanted = item.safeSourceOrdinals().stream()
                .filter(byOrdinal::containsKey)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        if (!wanted.isEmpty()) {
            return wanted.stream().map(byOrdinal::get).toList();
        }

        log.warn("segment '{}' cited no usable section ordinals ({}); falling back",
                item.title(), item.safeSourceOrdinals());
        return allSections.stream()
                .filter(s -> s.getRawText() != null && s.getRawText().length() > 200)
                .limit(2)
                .toList();
    }

    private String joinText(List<Section> sources) {
        return sources.stream()
                .map(s -> "## " + (s.getHeading() == null ? "Section" : s.getHeading())
                        + "\n" + (s.getRawText() == null ? "" : s.getRawText()))
                .collect(Collectors.joining("\n\n"));
    }

    private String joinFormulas(List<Section> sources) {
        String joined = sources.stream()
                .map(Section::getLatex)
                .filter(l -> l != null && !l.isBlank())
                .collect(Collectors.joining("\n"));
        return joined.isBlank() ? "(none)" : joined;
    }

    public record Result(int segmentsWritten, int storyboardsBuilt, String error) {
    }
}
