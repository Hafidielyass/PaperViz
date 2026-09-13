package dev.paperviz.rendering;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paperviz.config.PaperVizProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Client for the Python render service.
 *
 * The read timeout is long on purpose: a Manim render legitimately takes
 * minutes, and the service enforces its own hard ceiling, so the client should
 * outlast it rather than cutting off a job that is still working.
 */
@Component
public class RenderClient {

    private static final Logger log = LoggerFactory.getLogger(RenderClient.class);

    private final RestClient http;
    private final ObjectMapper objectMapper;

    public RenderClient(PaperVizProperties props,
                        RestClient.Builder builder,
                        ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(12));

        this.http = builder.clone()
                .baseUrl(props.getRender().getBaseUrl())
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * @param renderId  becomes the output filename, so it must be filesystem-safe
     * @param storyboardJson the validated storyboard, passed through verbatim
     */
    public RenderResponse render(String renderId, String storyboardJson, String quality) {
        Map<String, Object> body;
        try {
            body = Map.of(
                    "render_id", renderId,
                    "storyboard", objectMapper.readValue(storyboardJson, Map.class),
                    "quality", quality);
        } catch (Exception e) {
            throw new IllegalArgumentException("Storyboard is not valid JSON: " + e.getMessage(), e);
        }

        long started = System.currentTimeMillis();
        RenderResponse response = http.post()
                .uri("/render")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RenderResponse.class);

        log.info("render {} returned in {} ms", renderId, System.currentTimeMillis() - started);
        if (response == null) {
            throw new IllegalStateException("Render service returned an empty response.");
        }
        return response;
    }

    /**
     * Mirrors the render service's response. Field names are snake_case there;
     * Jackson maps them via the constructor property names below.
     */
    public record RenderResponse(
            boolean ok,
            @com.fasterxml.jackson.annotation.JsonProperty("render_id") String renderId,
            @com.fasterxml.jackson.annotation.JsonProperty("video_path") String videoPath,
            @com.fasterxml.jackson.annotation.JsonProperty("duration_seconds") Double durationSeconds,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_seconds") Double expectedSeconds,
            @com.fasterxml.jackson.annotation.JsonProperty("elapsed_seconds") Double elapsedSeconds,
            String error,
            @com.fasterxml.jackson.annotation.JsonProperty("log_tail") String logTail
    ) {
    }
}
