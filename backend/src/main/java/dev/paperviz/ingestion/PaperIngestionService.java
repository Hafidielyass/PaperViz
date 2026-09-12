package dev.paperviz.ingestion;

import dev.paperviz.config.PaperVizProperties;
import dev.paperviz.domain.model.Enums.PaperStatus;
import dev.paperviz.domain.model.Enums.SourceType;
import dev.paperviz.domain.model.Paper;
import dev.paperviz.domain.repo.PaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an uploaded PDF into a {@link Paper} row plus a stored file.
 *
 * Content-addressed: the same bytes uploaded twice by the same owner resolve to
 * the existing paper instead of reprocessing, which is the cheapest layer of the
 * caching the pipeline needs.
 */
@Service
public class PaperIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PaperIngestionService.class);

    private final PaperRepository papers;
    private final StorageService storage;
    private final PdfValidator validator;
    private final PaperVizProperties props;

    public PaperIngestionService(PaperRepository papers,
                                 StorageService storage,
                                 PdfValidator validator,
                                 PaperVizProperties props) {
        this.papers = papers;
        this.storage = storage;
        this.validator = validator;
        this.props = props;
    }

    /**
     * @return the paper, and whether it already existed (a cache hit)
     */
    @Transactional
    public IngestResult ingestUpload(byte[] bytes, String originalFilename) {
        String filename = sanitiseFilename(originalFilename);
        validator.validate(bytes, filename);

        UUID ownerId = props.getDefaultOwnerId();
        String sha256 = sha256(bytes);

        Optional<Paper> existing = papers.findByOwnerIdAndContentSha256(ownerId, sha256);
        if (existing.isPresent()) {
            Paper paper = existing.get();
            log.info("upload matched existing paper {} by content hash", paper.getId());
            return new IngestResult(paper, true);
        }

        String storagePath = storage.storePdf(ownerId, sha256, bytes);

        Paper paper = new Paper();
        paper.setOwnerId(ownerId);
        paper.setSourceType(SourceType.UPLOAD);
        paper.setOriginalFilename(filename);
        paper.setContentSha256(sha256);
        paper.setStoragePath(storagePath);
        paper.setStatus(PaperStatus.UPLOADED);
        // Provisional until GROBID reads the real one out of the document.
        paper.setTitle(stripExtension(filename));

        paper = papers.save(paper);
        log.info("ingested upload {} as paper {} ({} bytes)", filename, paper.getId(), bytes.length);
        return new IngestResult(paper, false);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Keeps a readable label without trusting it as a path component. */
    private String sanitiseFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "upload.pdf";
        }
        String base = raw.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isBlank()) {
            return "upload.pdf";
        }
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public record IngestResult(Paper paper, boolean cacheHit) {
    }
}
