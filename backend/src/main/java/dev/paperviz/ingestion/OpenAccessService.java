package dev.paperviz.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paperviz.ingestion.OpenAccessPolicy.Resolved;
import dev.paperviz.ingestion.UnpaywallClient.OaRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implements Route 2 of the open-access policy: turn a pasted link into stored
 * bytes, or refuse with a message that tells the person to upload the PDF instead.
 *
 * <ol>
 *   <li>Allowlisted host → direct fetch of the PDF we build from the link shape.</li>
 *   <li>Anything else → extract a DOI → ask Unpaywall → fetch <em>its</em> PDF URL,
 *       never the page the user pasted.</li>
 *   <li>Neither works, or no open copy exists → {@link IngestionException} (HTTP 422).</li>
 * </ol>
 *
 * All network I/O happens here, outside any transaction; persistence is delegated
 * to {@link PaperIngestionService} so the slow part never holds a connection.
 */
@Service
public class OpenAccessService {

    private static final Logger log = LoggerFactory.getLogger(OpenAccessService.class);

    private final PaperIngestionService ingestion;
    private final UnpaywallClient unpaywall;
    private final OpenAccessFetcher fetcher;
    private final ObjectMapper mapper;

    public OpenAccessService(PaperIngestionService ingestion,
                             UnpaywallClient unpaywall,
                             OpenAccessFetcher fetcher,
                             ObjectMapper mapper) {
        this.ingestion = ingestion;
        this.unpaywall = unpaywall;
        this.fetcher = fetcher;
        this.mapper = mapper;
    }

    /**
     * @return the paper and whether it was a content-hash cache hit
     */
    public PaperIngestionService.IngestResult ingestUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IngestionException("A link is required.");
        }
        URL url = normalize(rawUrl.trim());

        Resolved resolved;
        String proof;
        Map<String, Object> proofMap = new LinkedHashMap<>();

        Optional<Resolved> allowlisted = OpenAccessPolicy.resolveAllowlisted(url);
        if (allowlisted.isPresent()) {
            resolved = allowlisted.get();
            proofMap.put("method", "allowlist");
            proofMap.put("host", resolved.host());
            proofMap.put("pdfUrl", resolved.fetchUrl());
            proof = json(proofMap);
            log.info("open-access allowlist hit: {} → {}", url, resolved.fetchUrl());
        } else {
            String doi = OpenAccessPolicy.extractDoi(url.toExternalForm()).orElseThrow(() ->
                    new IngestionException(
                            "That host is not on the open-access allowlist and no DOI was found in the link. "
                                    + "PaperViz only fetches papers it can prove are open access; upload the PDF instead."));

            OaRecord record = unpaywall.lookup(doi).orElse(null);
            if (record == null || !record.isOa() || record.pdfUrl() == null) {
                throw new IngestionException(
                        "No legal open-access copy of " + doi + " was found. Upload the PDF instead.");
            }

            resolved = new Resolved(record.pdfUrl(), "unpaywall", hostOf(record.pdfUrl()), doi);
            proofMap.put("method", "unpaywall");
            proofMap.put("doi", doi);
            proofMap.put("pdfUrl", record.pdfUrl());
            proof = json(proofMap);
            log.info("open-access via Unpaywall ({}): {} → {}", doi, url, resolved.fetchUrl());
        }

        byte[] bytes = fetcher.fetchPdf(resolved);
        String filename = derivedFilename(resolved.fetchUrl(), url.getHost());
        PaperIngestionService.IngestResult result = ingestion.ingestUrl(bytes, filename, url.toExternalForm(), proof);
        log.info("open-access ingestion {}: stored {} bytes", result.paper().getId(), bytes.length);
        return result;
    }

    /**
     * Validates the pasted link without being useful as a scraper: http(s) only,
     * a bare DOI (with or without {@code doi:}) routes through Unpaywall, and a
     * link carrying credentials is a prompt to paste it somewhere we cannot.
     */
    private URL normalize(String raw) {
        if (raw.contains(" ") || raw.contains("\t")) {
            throw new IngestionException("That link contains spaces; paste the whole address.");
        }
        String s = raw;
        if (!s.contains("://") && OpenAccessPolicy.isBareDoi(s)) {
            s = "https://doi.org/" + s.replaceFirst("(?i)^doi\\s*[:.]\\s*", "");
        }
        URL url;
        try {
            url = new URI(s).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IngestionException("That link is not a valid URL or DOI.");
        }
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
            throw new IngestionException("Only http(s) links are supported.");
        }
        if (url.getUserInfo() != null) {
            throw new IngestionException("That link embeds credentials; PaperViz will not use them.");
        }
        return url;
    }

    /** A readable provisional filename; GROBID replaces the title anyway. */
    private String derivedFilename(String fetchUrl, String fallbackHost) {
        String path;
        try {
            path = new URL(fetchUrl).getPath();
        } catch (Exception e) {
            path = "";
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (!last.isBlank() && last.contains(".") && !last.endsWith("/")) {
            last = last.split("\\?")[0];
            if (!last.endsWith(".pdf")) {
                last = last + ".pdf";
            }
            return last;
        }
        return fallbackHost == null ? "paper.pdf" : fallbackHost + "-paper.pdf";
    }

    private String json(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the open-access evidence", e);
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            throw new IngestionException("The open-access copy has an unusable address.");
        }
    }
}