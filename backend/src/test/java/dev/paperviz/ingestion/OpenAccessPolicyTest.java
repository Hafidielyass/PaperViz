package dev.paperviz.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paperviz.ingestion.OpenAccessPolicy.Resolved;

class OpenAccessPolicyTest {

    @ParameterizedTest(name = "{0} allowlisted -> {1}")
    @CsvSource({
            "arxiv.org,              true",
            "export.arxiv.org,       true",
            "www.arxiv.org,          true",
            "www.biorxiv.org,        true",
            "connect.biorxiv.org,    true",
            "medrxiv.org,            true",
            "www.ncbi.nlm.nih.gov,   true",
            "pubmed.ncbi.nlm.nih.gov,true",
            "europepmc.org,          true",
            "openreview.net,         true",
            "papers.ssrn.com,        true",
            "ssrn.com,               true",
            "arxiv.org.evil.example, false",
            "evil-arxiv.org,         false",
            "biarxiv.org,            false",
            "ncbi.nlm.nih.gov.evil.example, false",
    })
    void allowlistMatchesOnDomainBoundary(String host, boolean expected) {
        assertThat(OpenAccessPolicy.matchesAllowlist(host)).isEqualTo(expected);
    }

    @Test
    void registrableDomainIsLastTwoLabels() {
        assertThat(OpenAccessPolicy.registrableDomain("arxiv.org")).isEqualTo("arxiv.org");
        assertThat(OpenAccessPolicy.registrableDomain("export.arxiv.org")).isEqualTo("arxiv.org");
        assertThat(OpenAccessPolicy.registrableDomain("www.mdpi.com")).isEqualTo("mdpi.com");
    }

    @Test
    void arxivAbsAndPdfBothResolveToCanonicalPdf() {
        assertThat(resolve("https://arxiv.org/abs/2401.00001v2").fetchUrl())
                .isEqualTo("https://arxiv.org/pdf/2401.00001v2");
        assertThat(resolve("https://arxiv.org/pdf/2401.00001").fetchUrl())
                .isEqualTo("https://arxiv.org/pdf/2401.00001");
        // Legacy category-style ids keep their prefix.
        assertThat(resolve("https://export.arxiv.org/abs/hep-th/9901001").fetchUrl())
                .isEqualTo("https://arxiv.org/pdf/hep-th/9901001");
    }

    @Test
    void arxivLinkWithoutAbsOrPdfIsRejected() {
        assertThatThrownBy(() -> resolve("https://arxiv.org/list/stat/recent"))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("arXiv");
    }

    @Test
    void preprintLandingResolvesToFullPdf() {
        assertThat(resolve("https://www.biorxiv.org/content/10.1101/2023.01.01.522478v1").fetchUrl())
                .isEqualTo("https://www.biorxiv.org/content/10.1101/2023.01.01.522478v1.full.pdf");
        assertThat(resolve("https://www.medrxiv.org/content/10.1101/2020.04.14.999999v2.full.pdf").fetchUrl())
                .isEqualTo("https://www.medrxiv.org/content/10.1101/2020.04.14.999999v2.full.pdf");
    }

    @Test
    void pmcArticleResolvesToPdfEndpoint() {
        assertThat(resolve("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1234567/").fetchUrl())
                .isEqualTo("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1234567/pdf/");
    }

    @Test
    void europePmcResolvesToRenderPdf() {
        assertThat(resolve("https://europepmc.org/article/PMC/3456789").fetchUrl())
                .isEqualTo("https://europepmc.org/articles/PMC3456789?pdf=render");
    }

    @Test
    void openReviewPapersResolveToPdfEndpoint() {
        assertThat(resolve("https://openreview.net/forum?id=AbC123").fetchUrl())
                .isEqualTo("https://openreview.net/pdf?id=AbC123");
        assertThat(resolve("https://openreview.net/pdf?id=AbC123").fetchUrl())
                .isEqualTo("https://openreview.net/pdf?id=AbC123");
    }

    @Test
    void ssrnAbstractResolvesToDeliveryPdf() {
        assertThat(resolve("https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4123456").fetchUrl())
                .isEqualTo("https://papers.ssrn.com/sol3/Delivery.cfm?abstractid=4123456");
    }

    @Test
    void nonAllowlistedHostDoesNotResolve() {
        assertThat(OpenAccessPolicy.resolveAllowlisted(url("https://www.nature.com/articles/s41586-022-04644-z")))
                .isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "https://doi.org/10.1101/2020.04.14.999999,                           10.1101/2020.04.14.999999",
            "doi:10.1007/s00134-004-2246-5,                                     10.1007/s00134-004-2246-5",
            "https://doi.org/10.1101/2020.04.14.999999?fromCover=1,              10.1101/2020.04.14.999999",
            "10.1101/2020.04.14.999999,                                          10.1101/2020.04.14.999999",
            "https://arxiv.org/abs/2401.00001,                                    (none)",
            // Journal landing pages carry no DOI in the URL; those are not resolvable without Crossref.
            "https://www.nature.com/articles/s41586-022-04644-z,                  (none)",
    })
    void doiExtraction(String input, String expected) {
        Optional<String> doi = OpenAccessPolicy.extractDoi(input);
        if ("(none)".equals(expected)) {
            assertThat(doi).isEmpty();
        } else {
            assertThat(doi).hasValue(expected);
        }
    }

    @Test
    void doiExtractionStripsTrailingPunctuation() {
        assertThat(OpenAccessPolicy.extractDoi("10.1234/foo.")).hasValue("10.1234/foo");
        assertThat(OpenAccessPolicy.extractDoi("(10.1234/bar)")).hasValue("10.1234/bar");
    }

    @ParameterizedTest(name = "bare DOI: \"{0}\"")
    @CsvSource({
            "10.1234/foo,   true",
            "doi: 10.1234/foo, true",
            "DOI:10.1234/foo, true",
            "https://arxiv.org/abs/2401.00001, false",
    })
    void bareDoiDetection(String input, boolean expected) {
        assertThat(OpenAccessPolicy.isBareDoi(input)).isEqualTo(expected);
    }

    private static Resolved resolve(String raw) {
        return OpenAccessPolicy.resolveAllowlisted(url(raw))
                .orElseThrow(() -> new AssertionError("expected an allowlisted resolution for " + raw));
    }

    private static URL url(String raw) {
        try {
            return new URL(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}