package dev.paperviz.api;

import dev.paperviz.config.PaperVizProperties;
import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Render;
import dev.paperviz.domain.repo.ConceptRepository;
import dev.paperviz.domain.repo.PaperRepository;
import dev.paperviz.rendering.RenderJobRunner;
import dev.paperviz.rendering.RenderJobRunner.Outcome;
import dev.paperviz.rendering.RenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class RenderController {

    private static final Logger log = LoggerFactory.getLogger(RenderController.class);

    private final RenderJobRunner renderJobs;
    private final RenderService renderService;
    private final ConceptRepository concepts;
    private final PaperRepository papers;
    private final PaperVizProperties props;

    public RenderController(RenderJobRunner renderJobs,
                            RenderService renderService,
                            ConceptRepository concepts,
                            PaperRepository papers,
                            PaperVizProperties props) {
        this.renderJobs = renderJobs;
        this.renderService = renderService;
        this.concepts = concepts;
        this.papers = papers;
        this.props = props;
    }

    /**
     * Renders one concept and waits for the result.
     *
     * Synchronous because a single render is a minute or two and the UI wants
     * to show the video immediately. Whole-paper rendering goes through the
     * async path.
     */
    @PostMapping("/concepts/{conceptId}/render")
    public RenderDtos.RenderResult render(@PathVariable UUID conceptId,
                                          @RequestParam(defaultValue = "medium") String quality) {
        requireConcept(conceptId);

        long started = System.currentTimeMillis();
        Outcome outcome = renderJobs.run(conceptId, quality);
        long elapsed = System.currentTimeMillis() - started;

        log.info("concept {} render finished in {} ms: ok={}", conceptId, elapsed, outcome.ok());

        return new RenderDtos.RenderResult(
                conceptId,
                outcome.ok(),
                outcome.ok() ? videoUrl(conceptId) : null,
                outcome.message(),
                elapsed);
    }

    /** Fire and forget: render every storyboarded concept in a paper. */
    @PostMapping("/papers/{paperId}/render")
    public RenderDtos.RenderBatchStarted renderPaper(@PathVariable UUID paperId,
                                                     @RequestParam(defaultValue = "medium") String quality) {
        requirePaper(paperId);
        List<Concept> animatable = concepts.findByPaperId(paperId).stream()
                .filter(Concept::isAnimate)
                .filter(c -> c.getStoryboardJson() != null && !c.getStoryboardJson().isBlank())
                .toList();

        animatable.forEach(c -> renderJobs.submit(c.getId(), quality));

        return new RenderDtos.RenderBatchStarted(paperId, animatable.size(),
                "Rendering %d animation(s). Each takes a minute or two."
                        .formatted(animatable.size()));
    }

    @GetMapping("/concepts/{conceptId}/render")
    public RenderDtos.RenderStatus status(@PathVariable UUID conceptId) {
        requireConcept(conceptId);
        return renderService.forConcept(conceptId)
                .map(r -> new RenderDtos.RenderStatus(
                        conceptId,
                        r.getStatus().name(),
                        r.getVideoPath() == null ? null : videoUrl(conceptId),
                        r.getDurationSeconds() == null ? null : r.getDurationSeconds().doubleValue(),
                        r.getRetryCount(),
                        r.getLastError()))
                .orElse(new RenderDtos.RenderStatus(conceptId, "NONE", null, null, 0, null));
    }

    /**
     * Streams the rendered video.
     *
     * Served through the API rather than as a static path so a video stays
     * reachable only through its owning paper, the same rule the source PDF
     * follows.
     */
    @GetMapping("/concepts/{conceptId}/video")
    public ResponseEntity<Resource> video(@PathVariable UUID conceptId) {
        requireConcept(conceptId);

        Render render = renderService.forConcept(conceptId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Nothing rendered yet."));
        if (render.getVideoPath() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Nothing rendered yet.");
        }

        Path root = Path.of(props.getMediaRoot()).toAbsolutePath().normalize();
        Path file = root.resolve(render.getVideoPath()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(NOT_FOUND, "The rendered file is missing.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                // Range support lets the browser seek instead of buffering the whole file.
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(file));
    }

    private Concept requireConcept(UUID conceptId) {
        return concepts.findById(conceptId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such concept."));
    }

    private void requirePaper(UUID paperId) {
        papers.findByIdAndOwnerId(paperId, props.getDefaultOwnerId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such paper."));
    }

    private String videoUrl(UUID conceptId) {
        return "/api/concepts/" + conceptId + "/video";
    }
}
