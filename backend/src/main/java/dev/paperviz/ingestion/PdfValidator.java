package dev.paperviz.ingestion;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Checks that an upload really is a PDF.
 *
 * The declared content type and the filename both come from the client, so
 * neither is trusted; the magic bytes are what decide.
 */
@Component
public class PdfValidator {

    /** Matches spring.servlet.multipart.max-file-size. */
    public static final long MAX_BYTES = 100L * 1024 * 1024;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public void validate(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new IngestionException("The uploaded file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IngestionException(
                    "That file is %.1f MB; the limit is %d MB."
                            .formatted(bytes.length / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        }
        if (!startsWithPdfMagic(bytes)) {
            throw new IngestionException(
                    "That does not look like a PDF. PaperViz reads PDFs; if you have the LaTeX "
                            + "source instead, arXiv links are supported on the URL tab.");
        }
    }

    /** Magic-byte check only, for paths that make mistakes of their own choosing. */
    public boolean looksLikePdf(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            return false;
        }
        return startsWithPdfMagic(bytes);
    }

    private boolean startsWithPdfMagic(byte[] bytes) {
        // Some producers emit a few junk bytes before the header, which readers
        // tolerate, so scan a small window rather than requiring offset 0.
        int window = Math.min(bytes.length - PDF_MAGIC.length, 1024);
        for (int offset = 0; offset <= window; offset++) {
            if (matchesAt(bytes, offset)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAt(byte[] bytes, int offset) {
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[offset + i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
