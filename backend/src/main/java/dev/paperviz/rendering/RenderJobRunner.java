package dev.paperviz.rendering;

import dev.paperviz.rendering.RenderClient.RenderResponse;
import dev.paperviz.rendering.RenderService.ClaimResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives one render: claim, call Manim, record the outcome.
 *
 * Separate bean from {@link RenderService} so the {@code @Async} and
 * {@code @Transactional} proxies do not swallow each other, and so the multi-minute
 * Manim call never sits inside a database transaction.
 */
@Component
public class RenderJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RenderJobRunner.class);

    private final RenderService renderService;
    private final RenderClient client;

    public RenderJobRunner(RenderService renderService, RenderClient client) {
        this.renderService = renderService;
        this.client = client;
    }

    @Async("paperVizExecutor")
    public void submit(UUID conceptId, String quality) {
        run(conceptId, quality);
    }

    @Async("paperVizExecutor")
    public void submitSegment(UUID segmentId, String quality) {
        runSegment(segmentId, quality);
    }

    /** Segment equivalent of {@link #run(UUID, String)}. */
    public Outcome runSegment(UUID segmentId, String quality) {
        ClaimResult claim;
        try {
            claim = renderService.claimSegment(segmentId);
        } catch (Exception e) {
            log.error("could not claim segment {} for rendering", segmentId, e);
            return new Outcome(false, null, e.getMessage());
        }
        return execute(claim, quality, "segment " + segmentId);
    }

    /** Synchronous entry point, used by the async path and for a single-render demo. */
    public Outcome run(UUID conceptId, String quality) {
        ClaimResult claim;
        try {
            claim = renderService.claim(conceptId);
        } catch (Exception e) {
            log.error("could not claim concept {} for rendering", conceptId, e);
            return new Outcome(false, null, e.getMessage());
        }

        return execute(claim, quality, "concept " + conceptId);
    }

    /** Shared body: both concepts and segments render the same way once claimed. */
    private Outcome execute(ClaimResult claim, String quality, String what) {
        if (claim.cacheHit()) {
            return new Outcome(true, claim.videoPath(), "Reused cached render.");
        }
        if (!claim.shouldRender()) {
            return new Outcome(false, null, claim.message());
        }

        UUID renderId = claim.renderId();
        try {
            // Outside any transaction: this is minutes of Manim.
            RenderResponse response = client.render(
                    renderId.toString(), claim.storyboard(), quality);

            if (!response.ok()) {
                log.warn("render {} failed: {}", renderId, response.error());
                renderService.fail(renderId, response.error());
                return new Outcome(false, null, response.error());
            }

            renderService.succeed(renderId, response.videoPath(), response.durationSeconds());
            log.info("render {} produced {} ({}s of video in {}s)",
                    renderId, response.videoPath(),
                    response.durationSeconds(), response.elapsedSeconds());
            return new Outcome(true, response.videoPath(), null);

        } catch (Exception e) {
            log.error("render {} for {} threw", renderId, what, e);
            renderService.fail(renderId, e.getMessage());
            return new Outcome(false, null, e.getMessage());
        }
    }

    public record Outcome(boolean ok, String videoPath, String message) {
    }
}
