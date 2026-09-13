package dev.paperviz.rendering;

import dev.paperviz.domain.model.Concept;
import dev.paperviz.domain.model.Enums.RenderStatus;
import dev.paperviz.domain.model.Render;
import dev.paperviz.domain.model.Segment;
import dev.paperviz.domain.repo.ConceptRepository;
import dev.paperviz.domain.repo.RenderRepository;
import dev.paperviz.domain.repo.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional half of rendering. The Manim call itself happens outside
 * any transaction, in {@link RenderJobRunner}.
 */
@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    /** Give up after this many attempts at one concept. */
    public static final int MAX_RETRIES = 3;

    private final RenderRepository renders;
    private final ConceptRepository concepts;
    private final SegmentRepository segments;

    public RenderService(RenderRepository renders,
                         ConceptRepository concepts,
                         SegmentRepository segments) {
        this.renders = renders;
        this.concepts = concepts;
        this.segments = segments;
    }

    /**
     * Claims a concept for rendering, or reports that there is nothing to do.
     *
     * A READY render whose cache key still matches is reused as-is: the same
     * storyboard always produces the same video, and a render is minutes of
     * compute.
     */
    @Transactional
    public ClaimResult claim(UUID conceptId) {
        Concept concept = concepts.findById(conceptId)
                .orElseThrow(() -> new IllegalArgumentException("No such concept: " + conceptId));

        String storyboard = concept.getStoryboardJson();
        if (storyboard == null || storyboard.isBlank() || storyboard.contains("\"problems\"")) {
            return ClaimResult.nothingToDo("This concept has no usable storyboard.");
        }

        String cacheKey = sha256(storyboard);

        Render render = renders.findByConceptId(conceptId).orElseGet(() -> {
            Render fresh = new Render();
            fresh.setConceptId(conceptId);
            return fresh;
        });

        if (render.getStatus() == RenderStatus.READY
                && cacheKey.equals(render.getCacheKey())
                && render.getVideoPath() != null) {
            log.info("concept {} already rendered from an identical storyboard", conceptId);
            return ClaimResult.cached(render.getId(), render.getVideoPath());
        }

        if (render.getStatus() == RenderStatus.RENDERING) {
            return ClaimResult.nothingToDo("This concept is already rendering.");
        }
        if (render.getRetryCount() >= MAX_RETRIES && !cacheKey.equals(render.getCacheKey())) {
            // A new storyboard resets the budget; the same one does not.
            render.setRetryCount(0);
        }
        if (render.getRetryCount() >= MAX_RETRIES) {
            return ClaimResult.nothingToDo(
                    "Rendering failed %d times for this storyboard.".formatted(MAX_RETRIES));
        }

        render.setStatus(RenderStatus.RENDERING);
        render.setCacheKey(cacheKey);
        render.setLastError(null);
        render = renders.save(render);

        return ClaimResult.claimed(render.getId(), storyboard);
    }

    @Transactional
    public void succeed(UUID renderId, String videoPath, Double durationSeconds) {
        renders.findById(renderId).ifPresent(render -> {
            render.setStatus(RenderStatus.READY);
            render.setVideoPath(videoPath);
            render.setDurationSeconds(durationSeconds == null
                    ? null : BigDecimal.valueOf(durationSeconds));
            render.setLastError(null);
            renders.save(render);
        });
    }

    @Transactional
    public void fail(UUID renderId, String error) {
        renders.findById(renderId).ifPresent(render -> {
            render.setStatus(RenderStatus.FAILED);
            render.setRetryCount(render.getRetryCount() + 1);
            render.setLastError(error == null ? "Unknown render failure"
                    : error.substring(0, Math.min(error.length(), 2000)));
            renders.save(render);
        });
    }

    /** Segment equivalent of {@link #claim(UUID)}. Same caching and retry rules. */
    @Transactional
    public ClaimResult claimSegment(UUID segmentId) {
        Segment segment = segments.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("No such segment: " + segmentId));

        String storyboard = segment.getStoryboardJson();
        if (storyboard == null || storyboard.isBlank() || storyboard.contains("\"problems\"")) {
            return ClaimResult.nothingToDo("This segment has no usable storyboard.");
        }

        String cacheKey = sha256(storyboard);

        Render render = renders.findBySegmentId(segmentId).orElseGet(() -> {
            Render fresh = new Render();
            fresh.setSegmentId(segmentId);
            return fresh;
        });

        if (render.getStatus() == RenderStatus.READY
                && cacheKey.equals(render.getCacheKey())
                && render.getVideoPath() != null) {
            log.info("segment {} already rendered from an identical storyboard", segmentId);
            return ClaimResult.cached(render.getId(), render.getVideoPath());
        }
        if (render.getStatus() == RenderStatus.RENDERING) {
            return ClaimResult.nothingToDo("This segment is already rendering.");
        }
        if (render.getRetryCount() >= MAX_RETRIES && !cacheKey.equals(render.getCacheKey())) {
            render.setRetryCount(0);
        }
        if (render.getRetryCount() >= MAX_RETRIES) {
            return ClaimResult.nothingToDo(
                    "Rendering failed %d times for this storyboard.".formatted(MAX_RETRIES));
        }

        render.setStatus(RenderStatus.RENDERING);
        render.setCacheKey(cacheKey);
        render.setLastError(null);
        render = renders.save(render);

        return ClaimResult.claimed(render.getId(), storyboard);
    }

    @Transactional(readOnly = true)
    public Optional<Render> forConcept(UUID conceptId) {
        return renders.findByConceptId(conceptId);
    }

    @Transactional(readOnly = true)
    public Optional<Render> forSegment(UUID segmentId) {
        return renders.findBySegmentId(segmentId);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param renderId   set when claimed or cached
     * @param storyboard set only when there is work to do
     */
    public record ClaimResult(boolean shouldRender, boolean cacheHit, UUID renderId,
                              String storyboard, String videoPath, String message) {

        static ClaimResult claimed(UUID renderId, String storyboard) {
            return new ClaimResult(true, false, renderId, storyboard, null, null);
        }

        static ClaimResult cached(UUID renderId, String videoPath) {
            return new ClaimResult(false, true, renderId, null, videoPath, "Reused cached render.");
        }

        static ClaimResult nothingToDo(String message) {
            return new ClaimResult(false, false, null, null, null, message);
        }
    }
}
