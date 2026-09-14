package dev.paperviz.ingestion;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure, unit-testable rules behind the open-access URL route — the
 * implementation of docs/OPEN-ACCESS-POLICY.md, with no I/O of its own.
 *
 * Two layers:
 * <ol>
 *   <li>Host allowlist, matched on the registrable-domain boundary so that
 *       {@code arxiv.org.evil.example} never matches while {@code export.arxiv.org} does.</li>
 *   <li>DOI extraction for the {@code doi/Unpaywall} fallback on every other host.</li>
 * </ol>
 *
 * DOAJ-indexed journals are deliberately not queried per-request: DOAJ only indexes
 * fully open-access journals, so Unpaywall — which already tracks them — reports
 * {@code is_oa=true} and supplies the open PDF. Resolving the ISSN via Crossref and
 * then asking DOAJ would reach the same answer with two extra outbound calls.
 */
public final class OpenAccessPolicy {

    /**
     * Registrable domains whose content we fetch directly. Two- and three-label
     * entries are matched by suffix on a label boundary, so all of arxiv.org,
     * export.arxiv.org, www.biorxiv.org, connect.biorxiv.org, papers.ssrn.com
     * and pubmed.ncbi.nlm.nih.gov are covered by the entries below.
     */
    private static final List<String> ALLOWLISTED_DOMAINS = List.of(
            "arxiv.org",          // arXiv
            "biorxiv.org",        // bioRxiv (+ connect.biorxiv.org)
            "medrxiv.org",        // medRxiv
            "ncbi.nlm.nih.gov",   // PubMed Central
            "europepmc.org",      // Europe PMC
            "openreview.net",     // OpenReview
            "ssrn.com");          // SSRN (+ papers.ssrn.com)

    private static final Pattern DOI = Pattern.compile(
            "(?:doi[:.])?(10\\.\\d{4,9}/[^\\s?#'\"<>]+)");

    private static final Pattern DOI_STRICT = Pattern.compile(
            "10\\.\\d{4,9}/[^\\s?#'\"<>]+");

    /** What turns one URL into the PDF we are allowed to fetch. */
    public record Resolved(String fetchUrl, String method, String host, String doi) {
    }

    private OpenAccessPolicy() {
    }

    /** Suffix match on a domain boundary: export.arxiv.org matches, arxiv.org.evil.example does not. */
    public static boolean matchesAllowlist(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return ALLOWLISTED_DOMAINS.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    /** The last two labels, just enough for a same-host redirect policy. */
    public static String registrableDomain(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String[] labels = h.split("\\.");
        if (labels.length <= 2) {
            return h;
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    /**
     * @return the direct PDF fetch target for an allowlisted host, otherwise empty
     * @throws IngestionException if the host matches the allowlist but the link
     *         shape is not one we can turn into a PDF
     */
    public static Optional<Resolved> resolveAllowlisted(URL url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);

        if (host.equals("arxiv.org") || host.endsWith(".arxiv.org")) {
            return Optional.of(new Resolved(arxivPdf(url), "allowlist", host, null));
        }
        if (host.equals("biorxiv.org") || host.endsWith(".biorxiv.org")
                || host.equals("medrxiv.org") || host.endsWith(".medrxiv.org")) {
            return Optional.of(new Resolved(preprintPdf(url), "allowlist", host, null));
        }
        if (host.equals("ncbi.nlm.nih.gov") || host.endsWith(".ncbi.nlm.nih.gov")) {
            return Optional.of(new Resolved(pmcPdf(url), "allowlist", host, null));
        }
        if (host.equals("europepmc.org") || host.endsWith(".europepmc.org")) {
            return Optional.of(new Resolved(europePmcPdf(url), "allowlist", host, null));
        }
        if (host.equals("openreview.net") || host.endsWith(".openreview.net")) {
            return Optional.of(new Resolved(openReviewPdf(url), "allowlist", host, null));
        }
        if (host.equals("ssrn.com") || host.endsWith(".ssrn.com")) {
            return Optional.of(new Resolved(ssrnPdf(url), "allowlist", host, null));
        }
        return Optional.empty();
    }

    /** @return whether the whole string is a bare DOI, with or without a {@code doi:} prefix */
    public static boolean isBareDoi(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim().replaceFirst("(?i)^doi\\s*[:.]\\s*", "");
        return DOI_STRICT.matcher(s).matches();
    }

    /** Extracts a DOI from a URL string; empty when the link carries none. */
    public static Optional<String> extractDoi(String text) {
        if (text == null) {
            return Optional.empty();
        }
        // Fragments delineate the document, not the DOI.
        Matcher m = DOI.matcher(text.split("#", 2)[0]);
        if (!m.find()) {
            return Optional.empty();
        }
        String doi = m.group(1);
        while (!doi.isEmpty() && ".,;:)]}".indexOf(doi.charAt(doi.length() - 1)) >= 0) {
            doi = doi.substring(0, doi.length() - 1);
        }
        return doi.isBlank() ? Optional.empty() : Optional.of(doi);
    }

    /** arXiv: /abs/id and /pdf/id both resolve to the canonical PDF endpoint. */
    private static String arxivPdf(URL url) {
        String path = url.getPath() == null ? "" : url.getPath();
        int idx = Math.max(path.lastIndexOf("/abs/"), path.lastIndexOf("/pdf/"));
        if (idx < 0) {
            throw new IngestionException(
                    "That arXiv link is not an /abs/ or /pdf/ address; PaperViz cannot find the paper in it.");
        }
        String id = path.substring(idx + 5);
        while (id.endsWith("/")) {
            id = id.substring(0, id.length() - 1);
        }
        if (id.isBlank()) {
            throw new IngestionException("That arXiv link has no paper id.");
        }
        return "https://arxiv.org/pdf/" + id;
    }

    /** bioRxiv/medRxiv: /content/&lt;doi&gt; is the landing page; the full text lives at &lt;doi&gt;.full.pdf. */
    private static String preprintPdf(URL url) {
        String s = url.toExternalForm();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int i = s.indexOf("/content/");
        if (i < 0) {
            throw new IngestionException(
                    "That preprint link does not point at a /content/<doi> page; PaperViz cannot find its PDF.");
        }
        if (s.endsWith(".full.pdf") || s.endsWith(".pdf")) {
            return s;
        }
        return s + ".full.pdf";
    }

    /** PubMed Central: /pmc/articles/PMCxxxx → /pmc/articles/PMCxxxx/pdf/. */
    private static String pmcPdf(URL url) {
        String path = url.getPath() == null ? "" : url.getPath();
        int i = path.indexOf("/pmc/articles/");
        if (i < 0) {
            throw new IngestionException(
                    "That PubMed link is not a /pmc/articles/ page; PaperViz cannot find its PDF.");
        }
        int end = path.indexOf('/', i + "/pmc/articles/".length());
        String article = end <= 0 ? path.substring(i) : path.substring(i, end);
        Matcher m = Pattern.compile("PMC\\d+").matcher(article);
        if (!m.find()) {
            throw new IngestionException("That PubMed Central link has no PMC id.");
        }
        return url.getProtocol() + "://" + url.getAuthority() + article + "/pdf/";
    }

    /** Europe PMC: /article/PMC/xxxx (canonical) or /article/PMCxxxx → the render-pdf URL (no HTML scraping needed). */
    private static String europePmcPdf(URL url) {
        String path = url.getPath() == null ? "" : url.getPath();
        Matcher m = Pattern.compile("PMC/?\\s*(\\d+)").matcher(path);
        if (!m.find()) {
            throw new IngestionException("That Europe PMC link has no PMC id.");
        }
        return "https://europepmc.org/articles/PMC" + m.group(1) + "?pdf=render";
    }

    /** OpenReview: every route (forum, notes, reply, pdf) carries the paper in ?id=… */
    private static String openReviewPdf(URL url) {
        String id = firstQueryParam(url, "id");
        if (id == null || id.isBlank()) {
            throw new IngestionException("That OpenReview link has no ?id= paper handle.");
        }
        return "https://openreview.net/pdf?id=" + id;
    }

    /** SSRN: papers.cfm?abstract_id=… → the Delivery.cfm PDF of the same abstract. */
    private static String ssrnPdf(URL url) {
        String aid = firstQueryParam(url, "abstract_id");
        if (aid == null) {
            aid = firstQueryParam(url, "abstractid");
        }
        if (aid == null) {
            throw new IngestionException("That SSRN link has no abstract_id.");
        }
        return "https://papers.ssrn.com/sol3/Delivery.cfm?abstractid=" + aid;
    }

    private static String firstQueryParam(URL url, String name) {
        String query = url.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!key.equals(name)) {
                continue;
            }
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }
        return null;
    }
}