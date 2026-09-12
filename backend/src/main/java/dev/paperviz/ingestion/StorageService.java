package dev.paperviz.ingestion;

import dev.paperviz.config.PaperVizProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Owns the media root. Files are addressed by owner and content hash, never by
 * a user-supplied name, so a crafted filename cannot escape the root or collide
 * with another user's paper.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Path root;

    public StorageService(PaperVizProperties props) {
        this.root = Path.of(props.getMediaRoot()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureWritable() {
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".probe", null);
            Files.delete(probe);
            log.info("media root ready at {}", root);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Media root " + root + " is not writable. In compose this is the shared "
                            + "`media` volume — check that the container user is in group 10100.", e);
        }
    }

    /**
     * Stores the PDF for a paper.
     *
     * @return the path relative to the media root, which is what goes in the database
     */
    public String storePdf(UUID ownerId, String contentSha256, byte[] bytes) {
        Path relative = Path.of("papers", ownerId.toString(), contentSha256 + ".pdf");
        Path target = resolve(relative.toString());
        try {
            Files.createDirectories(target.getParent());
            // Write to a sibling temp file then move, so a crash mid-write never
            // leaves a truncated file sitting at a hash-addressed path.
            Path tmp = Files.createTempFile(target.getParent(), ".upload", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return relative.toString().replace('\\', '/');
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store PDF for owner " + ownerId, e);
        }
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + relativePath, e);
        }
    }

    /** Resolves against the root and refuses anything that escapes it. */
    private Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the media root: " + relativePath);
        }
        return resolved;
    }
}
