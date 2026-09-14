package dev.paperviz.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import dev.paperviz.config.PaperVizProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Talks to the Unpaywall API for the non-allowlisted path: we hand it a DOI,
 * it tells us whether a legal open-access copy exists and where the PDF lives.
 *
 * The email is required by Unpaywall's service terms and is also what makes the
 * request eligible for their polite pool.
 */
@Component
public class UnpaywallClient {

    private final RestClient http;
    private final PaperVizProperties props;

    UnpaywallClient(PaperVizProperties props) {
        this.props = props;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public record OaRecord(boolean isOa, String pdfUrl) {
    }

    /**
     * @throws IngestionException if the lookup itself cannot complete, so callers
     *         never leak a transport error to the UI
     */
    public Optional<OaRecord> lookup(String doi) {
        String email = props.getUnpaywall().getEmail();
        if (email == null || email.isBlank()) {
            throw new IngestionException(
                    "Resolving a link through Unpaywall needs a contact email. "
                            + "Set PAPERVIZ_UNPAYWALL_EMAIL in the .env file. "
                            + "(PDF uploads do not use it.)");
        }

        String base = props.getUnpaywall().getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String endpoint = base + "/v2/" + encode(doi) + "?email=" + encode(email);

        JsonNode root;
        try {
            root = http.get().uri(endpoint).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            throw new IngestionException("Unpaywall answered with HTTP " + e.getStatusCode().value()
                    + " for DOI " + doi + "; try again shortly or upload the PDF.");
        } catch (RuntimeException e) {
            throw new IngestionException("Could not reach the Unpaywall API for DOI " + doi + ".");
        }
        if (root == null) {
            throw new IngestionException("Unpaywall returned nothing for DOI " + doi + ".");
        }

        boolean isOa = root.path("is_oa").asBoolean(false);
        String pdf = root.path("best_oa_location").path("url_for_pdf").asText(null);
        if (pdf == null || pdf.isBlank()) {
            return Optional.of(new OaRecord(isOa, null));
        }
        return Optional.of(new OaRecord(isOa, pdf));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}