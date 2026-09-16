package dev.paperviz.api;

import dev.paperviz.config.PaperVizProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage-1 proof that every container can see every other container.
 * Reports one line per dependency rather than a single opaque UP/DOWN, so a
 * broken link is obvious from the response body.
 */
@RestController
@RequestMapping("/api")
public class SystemHealthController {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthController.class);

    private final JdbcTemplate jdbc;
    private final PaperVizProperties props;
    private final RestClient http;
    private final String ollamaBaseUrl;

    public SystemHealthController(JdbcTemplate jdbc,
                                  PaperVizProperties props,
                                  RestClient.Builder builder,
                                  org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        this.props = props;
        this.http = builder.build();
        this.ollamaBaseUrl = env.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "paperviz-backend");
        body.put("timestamp", Instant.now().toString());

        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("postgres", check(this::pingPostgres));
        deps.put("ollama", check(this::pingOllama));
        deps.put("grobid", check(() -> pingHttp(props.getGrobid().getBaseUrl() + "/api/isalive")));
        deps.put("renderService", check(() -> pingHttp(props.getRender().getBaseUrl() + "/health")));
        body.put("dependencies", deps);

        boolean allUp = deps.values().stream()
                .allMatch(v -> "UP".equals(((Map<?, ?>) v).get("status")));
        body.put("status", allUp ? "UP" : "DEGRADED");
        return body;
    }

    private Map<String, Object> check(Probe probe) {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String detail = probe.run();
            result.put("status", "UP");
            result.put("detail", detail);
        } catch (Exception e) {
            log.debug("dependency probe failed", e);
            result.put("status", "DOWN");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
        return result;
    }

    /**
     * Reports whether the model is on the GPU, not merely that Ollama answers.
     *
     * Reachability and capability are different things here: with the stack
     * started without the GPU overlay, /api/tags responds perfectly while every
     * actual generation is OOM-killed, because the 8B model needs about 5.5 GiB
     * of system RAM on CPU. That failure is invisible until a paper dies
     * several minutes in, so it belongs on the health page.
     */
    private String pingOllama() {
        String tags = http.get().uri(ollamaBaseUrl + "/api/tags").retrieve().body(String.class);
        int models = tags == null ? 0 : tags.split("\"name\"").length - 1;

        String accelerator = "unknown";
        try {
            String ps = http.get().uri(ollamaBaseUrl + "/api/ps").retrieve().body(String.class);
            if (ps != null && ps.contains("size_vram")) {
                // size_vram of 0 means the weights are in system RAM.
                accelerator = ps.matches("(?s).*\"size_vram\"\s*:\s*0[,}].*") ? "CPU" : "GPU";
            } else if (ps != null) {
                accelerator = "idle";
            }
        } catch (Exception e) {
            accelerator = "unknown";
        }

        return "%d model(s) | running on %s".formatted(models, accelerator);
    }

    private String pingPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        Integer vectorInstalled = jdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'", Integer.class);
        return (version == null ? "unknown" : version.split(",")[0])
                + " | pgvector=" + (vectorInstalled != null && vectorInstalled > 0);
    }

    private String pingHttp(String url) {
        String body = http.get().uri(url).retrieve().body(String.class);
        if (body == null) {
            return "empty response";
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body.trim();
    }

    @FunctionalInterface
    private interface Probe {
        String run() throws Exception;
    }
}
