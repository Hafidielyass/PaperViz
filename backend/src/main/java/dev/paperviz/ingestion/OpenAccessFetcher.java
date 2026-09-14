package dev.paperviz.ingestion;

import dev.paperviz.config.PaperVizProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Fetches the bytes for an already-resolved open-access source.
 *
 * Redirect-following is deliberately NOT left to the JDK's default
 * {@link HttpURLConnection} (which follows 3xx for GETs automatically and
 * would turn any hop into an unchecked request): each connection is told
 * explicitly not to follow, and every hop is re-checked against the SSRF
 * guard and the registrable domain the source resolved to, so a link that
 * redirects off the open-access path (say onto the private Docker network)
 * dies fast. Five hops is plenty for any legitimate inter-page shuffle.
 */
@Component
public class OpenAccessFetcher {

    private static final int MAX_HOPS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 3 * 60_000;

    private final SsrfGuard ssrf;
    private final PdfValidator validator;
    private final PaperVizProperties props;

    OpenAccessFetcher(SsrfGuard ssrf, PdfValidator validator, PaperVizProperties props) {
        this.ssrf = ssrf;
        this.validator = validator;
        this.props = props;
    }

    /**
     * @throws IngestionException with a user-facing reason when the fetch fails
     *         or the result is not a usable PDF
     */
    public byte[] fetchPdf(OpenAccessPolicy.Resolved source) {
        String approvedDomain = OpenAccessPolicy.registrableDomain(hostOfOrThrow(source.fetchUrl()));
        String current = source.fetchUrl();

        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URL url = toUrl(current, "The open-access source returned a malformed address.");
            ssrf.check(url.getHost());
            if (!OpenAccessPolicy.registrableDomain(url.getHost()).equals(approvedDomain)) {
                throw new IngestionException(
                        "The source redirected off the approved open-access host (" + url.getHost()
                                + "), so PaperViz stopped.");
            }

            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false); // the loop below decides
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty(org.springframework.http.HttpHeaders.USER_AGENT, userAgent());
                connection.setRequestProperty("Accept", "application/pdf, application/octet-stream;q=0.9");
                connection.setRequestProperty("Accept-Encoding", "gzip");

                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IngestionException("The source redirected without giving a target.");
                    }
                    try {
                        current = url.toURI().resolve(location).toString();
                    } catch (java.net.URISyntaxException e) {
                        throw new IngestionException("The source redirected to a malformed address.");
                    }
                    continue;
                }
                if (status != 200) {
                    throw new IngestionException(
                            "Open-access fetch failed with HTTP " + status + " from " + url.getHost() + ".");
                }

                byte[] body = readBody(connection);
                if (body.length > PdfValidator.MAX_BYTES) {
                    throw new IngestionException("That PDF is larger than the 100 MB limit.");
                }
                if (!validator.looksLikePdf(body)) {
                    throw new IngestionException(
                            "That link did not resolve to a PDF — " + url.getHost()
                                    + " returned an HTML page instead. Some services put an interstitial in "
                                    + "front of the file; paste the direct /pdf or .full.pdf link, or upload the PDF.");
                }
                return body;
            } catch (IngestionException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new IngestionException("Could not reach " + current + "; the host may be blocking automated "
                        + "downloads. Try uploading the PDF instead.");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new IngestionException("Too many redirects fetching the open-access copy.");
    }

    private byte[] readBody(HttpURLConnection connection) throws IOException {
        String encoding = connection.getContentEncoding();
        boolean gzip = encoding != null && encoding.toLowerCase().contains("gzip");
        InputStream in = gzip ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream();

        try (in) {
            return readFully(in);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > PdfValidator.MAX_BYTES) {
                throw new IOException("response exceeds the size limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String userAgent() {
        String email = props.getUnpaywall().getEmail();
        String contact = email == null || email.isBlank() ? "the operator of this instance" : email;
        return "PaperViz/0.1 (open-access ingestion; contact " + contact + ")";
    }

    private static URL toUrl(String value, String failure) {
        try {
            return URI.create(value).toURL();
        } catch (Exception e) {
            throw new IngestionException(failure);
        }
    }

    private static String hostOfOrThrow(String url) {
        return toUrl(url, "The resolved open-access source is not a valid URL.").getHost();
    }
}