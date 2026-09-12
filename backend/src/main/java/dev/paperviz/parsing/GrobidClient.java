package dev.paperviz.parsing;

import dev.paperviz.config.PaperVizProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Calls GROBID's full-text endpoint and hands back raw TEI-XML.
 *
 * Header consolidation is deliberately off: it would make GROBID call out to
 * CrossRef, which is slow, rate-limited, and an outbound dependency we do not
 * want in the ingestion path.
 */
@Component
public class GrobidClient {

    private static final Logger log = LoggerFactory.getLogger(GrobidClient.class);

    private final RestClient http;

    public GrobidClient(PaperVizProperties props, RestClient.Builder builder) {
        // A large PDF can take GROBID a couple of minutes on CPU.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(5));

        this.http = builder.clone()
                .baseUrl(props.getGrobid().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * @param pdfBytes the raw PDF
     * @param filename original filename, passed through for GROBID's logs only
     * @return TEI-XML document
     */
    public String processFullText(byte[] pdfBytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("input", new NamedByteArrayResource(pdfBytes, filename));
        form.add("consolidateHeader", "0");
        form.add("consolidateCitations", "0");
        form.add("segmentSentences", "0");

        long started = System.currentTimeMillis();
        String tei = http.post()
                .uri("/api/processFulltextDocument")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_XML)
                .body(form)
                .retrieve()
                .body(String.class);

        log.info("GROBID processed {} ({} bytes) in {} ms",
                filename, pdfBytes.length, System.currentTimeMillis() - started);

        if (tei == null || tei.isBlank()) {
            throw new IllegalStateException("GROBID returned an empty document for " + filename);
        }
        return tei;
    }

    public boolean isAlive() {
        try {
            String body = http.get().uri("/api/isalive").retrieve().body(String.class);
            return body != null && body.contains("true");
        } catch (Exception e) {
            log.warn("GROBID isalive check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Multipart needs a filename on the part, which plain ByteArrayResource does not carry. */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
