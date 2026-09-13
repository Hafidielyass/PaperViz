package dev.paperviz.api;

import java.util.UUID;

/** Response shapes for rendering. */
public final class RenderDtos {

    private RenderDtos() {
    }

    public record RenderResult(
            UUID conceptId,
            boolean ok,
            String videoUrl,
            String message,
            long elapsedMs
    ) {
    }

    public record RenderStatus(
            UUID conceptId,
            String status,
            String videoUrl,
            Double durationSeconds,
            int retryCount,
            String lastError
    ) {
    }

    public record RenderBatchStarted(
            UUID paperId,
            int queued,
            String message
    ) {
    }
}
